(ns relatum.schema
  "Declarative table schemas, with the effects at the edge.

  The planning - parsing a declaration, resolving references, deriving the
  engine's constraint operations - is pure and lives in relatum.plan, where
  writ proves it. This namespace is the edge around it: it owns the global
  registry (a table declared here is visible to every later query), the
  ex-info policy for a rejected declaration, and the create-table macro that
  fails the build rather than the first insert.

  A column is [name type & attributes] where attributes may be
  :primary :key, :required, :nullable, :unique, :default v, :check form,
  :references table, and :on-delete :cascade.

  Only :real carries a type hint, and only because jolt coerces ^double.
  jolt treats ^long/^int as contracts that neither coerce nor reject, so
  integer columns are enforced by validation instead."
  (:require [clojure.string :as str]
            [relatum.plan :as plan]
            [relatum.type :as type]))

;; ---------------------------------------------------------------------------
;; verdicts into exceptions

(defn rejected
  "Turn a failed planning verdict into the ex-info this layer reports.

  The verdict's :error becomes :relatum/error in the ex-data, and its message
  becomes the exception message, so nothing has to repeat the diagnosis."
  [verdict]
  (ex-info (:message verdict)
           (-> verdict (dissoc :ok :error :message)
               (assoc :relatum/error (:error verdict)))))

(defn value!
  "The value of a planning verdict, or throw if the plan was rejected."
  [verdict]
  (if (plan/ok? verdict)
    (:value verdict)
    (throw (rejected verdict))))

;; ---------------------------------------------------------------------------
;; types

(defn parse-type
  "Parse a DDL type, throwing with {:relatum/error :unknown-type} when the
  declaration is not a type this library knows. The pure vocabulary lives in
  relatum.type; this is the reporting wrapper around it."
  [declared]
  (or (type/parse-type declared)
      (throw (rejected
              {:ok false :error :unknown-type
               :message (str "Unknown column type " (pr-str declared))
               :declared declared :known (type/known-types)}))))

(def conforms? type/conforms?)
(def varchar-conforms? type/varchar-conforms?)

(defn known-types [] (type/known-types))

;; ---------------------------------------------------------------------------
;; tables

(defonce ^:private registry (atom {}))
(defonce ^:private index-registry (atom {}))
;; bumped on every registry/index mutation, so access-indexes can cache its
;; derivation instead of recomputing it on every read
(defonce ^:private schema-gen (atom 0))

(defn- bump-gen! [] (swap! schema-gen inc))

(def ^:private table-options
  "What create-table accepts after the columns."
  #{:unique :record})

(defn build-table*
  "Validate a declaration into a table description, throwing on a bad one. The
  checking is relatum.plan/build-table's; this is the throwing call site.
  `opts` may carry :unique [[col ...] ...]; an option relatum doesn't know is
  rejected rather than silently dropped."
  ([name cols] (build-table* name cols nil))
  ([name cols opts]
   (when-let [unknown (seq (remove table-options (keys opts)))]
     (throw (ex-info (str "Unknown create-table option(s) " (pr-str (vec unknown)))
                     {:relatum/error :unknown-table-option :table name
                      :options (vec unknown) :known (vec (sort table-options))})))
   (value! (plan/build-table {:name name :columns cols :unique (:unique opts)}))))

(defn create-table*
  "Register a table schema at runtime. Validates the declaration and returns
  the table. Use this for schemas loaded from data; prefer the create-table
  macro for tables known at compile time.

    (create-table* :layer [[:id :int :primary :key] [:doc-id :int] [:position :int]]
                   :unique [[:doc-id :position]])"
  [name cols & {:as opts}]
  (let [t (build-table* name cols opts)]
    (swap! registry assoc name t)
    (bump-gen!)
    t))

(defn table
  "The registered table for name, or nil."
  [name]
  (get @registry name))

(defn tables
  "All registered tables, keyed by name."
  []
  @registry)

(defn forget
  "Drop a table from the registry, and with it any indexes declared on it.
  Returns the table removed."
  [name]
  (let [t (get @registry name)]
    (swap! registry dissoc name)
    (swap! index-registry
           (fn [idx] (into {} (remove #(= name (-> % val :table))) idx)))
    (bump-gen!)
    t))

(defn column
  "The column description for a table's column, or nil."
  [table-name column-name]
  (plan/column (table table-name) column-name))

;; ---------------------------------------------------------------------------
;; access indexes
;; The registry above owns tables; this one owns indexes declared on top of
;; them. Derived indexes (pk, unique columns, foreign keys) are not stored -
;; they are recomputed from the table on demand, so they can never drift from
;; the declaration they came from.

(defn create-index
  "Declare a composite access index over a registered table, e.g

    (create-index :order [:customer :status])

  opts may carry :type (:hash, the default, or :btree for ordered access).
  Registers it under its derived name (:order/by-customer+status) and returns
  the descriptor. Throws the schema layer's ex-info on a rejected declaration
  and on a duplicate registration."
  [table-name cols & {:as opts}]
  (let [t (table table-name)]
    (when-not t
      (throw (ex-info (str "Unknown table " table-name)
                      {:relatum/error :unknown-table :table table-name
                       :known (vec (keys (tables)))})))
    (let [idx (value! (plan/build-index t cols opts))
          existing (get @index-registry (:name idx))]
      (when (and existing (not= existing idx))
        (throw (rejected {:ok false :error :index-name-conflict
                          :message (str "Index " (:name idx) " is already declared")
                          :table table-name :index (:name idx)
                          :existing existing})))
      (swap! index-registry assoc (:name idx) idx)
      (bump-gen!)
      idx)))

(defn forget-index
  "Drop a declared index. Returns the descriptor removed."
  [index-name]
  (let [i (get @index-registry index-name)]
    (swap! index-registry dissoc index-name)
    (bump-gen!)
    i))

(defonce ^:private access-cache (volatile! {:gen -1 :indexes {}}))

(defn access-indexes
  "Every access index this process serves, keyed by name: declared indexes
  plus the ones derived from each registered table. Reads go through this
  map; core/create-db materializes all of them. Derived indexes are
  recomputed only when the schema changed since the last call."
  []
  (let [g @schema-gen]
    (if (= g (:gen @access-cache))
      (:indexes @access-cache)
      (loop []
        (let [g0 @schema-gen
              all (merge (into {} (comp (mapcat plan/derived-indexes)
                                        (map (juxt :name identity)))
                               (vals @registry))
                         @index-registry)]
          ;; every mutation bumps the generation, so an unchanged gen across
          ;; the build proves the snapshot is consistent; otherwise try again
          ;; rather than caching a mixed-generation map
          (if (= g0 @schema-gen)
            (do (vreset! access-cache {:gen g0 :indexes all})
                all)
            (recur)))))))

(defn column-names [table-name]
  (plan/column-names (table table-name)))

;; ---------------------------------------------------------------------------
;; row coercion / validation

(defn coerce-row
  "Validate and coerce a row against a table schema. Returns a new map
  containing only declared columns. Throws ex-info with ex-data
  {:relatum/error ...} on the first problem found."
  [table row]
  (value! (plan/validate-row table row)))

;; ---------------------------------------------------------------------------
;; engine constraints

(defn ddl->constraints
  "Translate a table into relvar constraint operations the engine understands:
  [:req ...] for non-nullable columns, [:unique ...] for unique/primary keys,
  one :fk op per reference, and one [:check {:pred ... :error ...}] per column
  for its declared type.

  References resolve against the registry, because a foreign key names a table
  by name and the registry is where names are bound."
  [table]
  (value! (plan/constraints table @registry)))

(defn constraint-query
  "The relvar query that installs a table's declared constraints. Feed it to
  the engine's mat so the declared shape is enforced by the database:

    (rel/mat {} (schema/constraint-query (schema/table :fruit)))"
  [table]
  (let [ops (ddl->constraints table)]
    (into [[:from (:name table)]]
          (when (seq ops) [(into [:constrain] ops)]))))

(defn describe
  "A plain-data description of a table, safe to pr-str for an agent to read."
  [table-name]
  (plan/describe (table table-name)))

(defn ddl
  "Render a table back to DDL text, close to what create-table accepts."
  [table-name]
  (plan/render (table table-name)))

;; ---------------------------------------------------------------------------
;; compile-time tables

(defn- type-sym [table-name]
  (let [parts (-> (str table-name)
                  (str/replace #"^:" "")
                  (str/split #"[/:._-]+"))]
    (symbol (apply str (map str/capitalize parts)))))

(defn record-fields
  "The positional field vector for a table's record type. Non-nullable :real
  columns get a ^double hint so jolt coerces on construction; nullable ones
  cannot, because a hint would reject the nil."
  [table]
  (mapv (fn [c]
          (if (and (= :real (-> c :type :kind)) (not (:nullable? c)))
            (with-meta (symbol (name (:name c))) {:tag 'double})
            (symbol (name (:name c)))))
        (:columns table)))

(defn create-table-form
  "The form create-table expands to: register the schema and, unless disabled,
  generate a record type named after the table in the caller's namespace."
  [table-name cols opts]
  (let [t (build-table* table-name cols opts)
        unique (:unique opts)]
    (if (false? (:record opts))
      `(do (create-table* ~table-name ~cols :unique ~unique) nil)
      (let [rsym (type-sym table-name)]
        `(do
           (create-table* ~table-name ~cols :unique ~unique)
           (defrecord ~rsym ~(record-fields t))
           nil)))))

(defmacro create-table
  "Declare a table at compile time.

    (create-table :fruit
      [[:name \"varchar(32)\" :primary :key]
       [:appearance \"varchar(32)\"]
       [:cost :int]
       [:grade :real]])

  The declaration is validated when this macro expands, so an unknown type,
  duplicate column, or contradictory attribute is a compile error rather than
  something you discover at runtime. Also defrecords a type named after the
  table (Fruit) in the calling namespace. Pass :record false to skip it.

  :unique [[:doc-id :position] ...] declares combinations of columns that must
  be unique as a tuple (SQL's table-level UNIQUE (doc_id, position)); a write
  that repeats one is rejected with :unique-key-violation."
  [table-name cols & {:as opts}]
  (create-table-form table-name cols opts))
