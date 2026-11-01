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

(defn build-table*
  "Validate a declaration into a table description, throwing on a bad one. The
  checking is relatum.plan/build-table's; this is the throwing call site."
  [name cols]
  (value! (plan/build-table {:name name :columns cols})))

(defn create-table*
  "Register a table schema at runtime. Validates the declaration and returns
  the table. Use this for schemas loaded from data; prefer the create-table
  macro for tables known at compile time."
  [name cols]
  (let [t (build-table* name cols)]
    (swap! registry assoc name t)
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
  "Drop a table from the registry. Returns the table removed."
  [name]
  (let [t (get @registry name)] (swap! registry dissoc name) t))

(defn column
  "The column description for a table's column, or nil."
  [table-name column-name]
  (plan/column (table table-name) column-name))

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
                  (str/split #"[-_.]+"))]
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
  (let [t (build-table* table-name cols)]
    (if (false? (:record opts))
      `(do (create-table* ~table-name ~cols) nil)
      (let [rsym (type-sym table-name)]
        `(do
           (create-table* ~table-name ~cols)
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
  table (Fruit) in the calling namespace. Pass :record false to skip it."
  [table-name cols & {:as opts}]
  (create-table-form table-name cols opts))
