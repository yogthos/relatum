(ns relatum.plan
  "Pure DDL planning: an agent's declaration in, a validated table description
  and its engine constraints out.

  Deliberately pure and throw-free. Every function returns a verdict map,
  {:ok true :value ...} or {:ok false :error <keyword> :message <string> ...},
  so the laws here can prove the planner itself is total and consistent.
  Deciding what a rejection *means* - an ex-info, a retry, an exit code - is
  relatum.schema's job at the edge.

  A table is plain data, not a record:

    {:name :person
     :columns [{:name :id :type {...} :primary? true ...}]
     :pk [:id] :uniques [:id] :checks [[:id expr]] :fks [{...}]}

  Plain data keeps it pr-str-able for agent introspection and keeps the spec
  free of record accessors."
  (:require [clojure.string :as str]
            [relatum.type :as type]))

;; ---------------------------------------------------------------------------
;; verdicts

(defn- succeed [value] {:ok true :value value})

(defn- fail
  [error message more]
  (assoc more :ok false :error error :message message))

(defn ok?
  "Did a verdict succeed?"
  [verdict]
  (true? (:ok verdict)))

;; ---------------------------------------------------------------------------
;; columns

(def ^:private column-attrs
  #{:primary :primary-key :key :required :not-null :nullable :null :unique
    :default :check :references :on-delete :on :auto})

(defn known-attributes
  "The column attributes a declaration may carry, sorted so error messages list
  them the same way every time."
  []
  (vec (sort column-attrs)))

(defn read-attributes
  "Fold a column's attribute tail into an options map, as a verdict.

  The value-taking attributes (:default :check :references :on-delete :on)
  consume the next form; the flags leave it to be read as the next attribute.
  A value-taking attribute whose value is absent or nil is rejected - the
  missing-value case would silently drop the attribute's meaning, and an
  explicit nil default would mean no default at all."
  [table column attrs]
  (loop [pending (seq attrs) acc {}]
    (cond
      (empty? pending) (succeed acc)
      (= :primary (first pending))
      (recur (if (= :key (second pending)) (nthrest pending 2) (rest pending))
             (assoc acc :primary? true))
      (#{:primary-key :key} (first pending))
      (recur (rest pending) (assoc acc :primary? true))
      (#{:required :not-null} (first pending))
      (recur (rest pending) (assoc acc :required? true))
      (#{:nullable :null} (first pending))
      (recur (rest pending) (assoc acc :nullable? true))
      (= :unique (first pending))
      (recur (rest pending) (assoc acc :unique? true))
      (= :auto (first pending))
      (recur (rest pending) (assoc acc :auto? true))
      (and (#{:default :check :references :on-delete :on} (first pending))
           (nil? (second pending)))
      (fail :missing-attribute-value
            (str "Attribute " (name (first pending)) " of " column
                 " needs a value")
            {:table table :column column :attribute (first pending)
             :allowed (known-attributes)})
      (#{:default :check} (first pending))
      (recur (nthrest pending 2) (assoc acc (first pending) (second pending)))
      (= :references (first pending))
      (recur (nthrest pending 2) (assoc acc :references (second pending)))
      (= :on-delete (first pending))
      (recur (nthrest pending 2) (assoc acc :on-delete (second pending)))
      (= :on (first pending))
      (recur (nthrest pending 2) (assoc acc :on (second pending)))
      :else
      (fail :unknown-attribute
            (str "Unknown column attribute " (pr-str (first pending)) " on " column)
            {:table table :column column :attribute (first pending)
             :allowed (known-attributes)}))))

(defn- column-from
  "The column description for an already-typed declaration, or a verdict saying
  the attributes contradict each other."
  [table col nm declared opts]
  (let [o (:value opts)
        primary? (true? (:primary? o))
        nullable? (true? (:nullable? o))
        required? (true? (:required? o))
        on-delete (:on-delete o)]
    (cond
      (and primary? nullable?)
      (fail :nullable-primary-key
            (str "Column " nm " is a primary key and cannot be nullable")
            {:table table :column nm})

      (and required? nullable?)
      (fail :required-and-nullable
            (str "Column " nm " cannot be both required and nullable; a nullable column is not required")
            {:table table :column nm})

      (and (some? on-delete) (not (#{:cascade :delete} on-delete)))
      (fail :unknown-on-delete
            (str "Column " nm " has :on-delete " (pr-str on-delete)
                 ", but :cascade is the only supported value")
            {:table table :column nm :on-delete on-delete})

      :else
      (succeed
       {:name nm
        :type (type/parse-type declared)
        :primary? primary?
        ;; a primary key is necessarily required and unique, derived not declared
        :nullable? (and nullable? (not primary?))
        :required? (or primary? required? (not nullable?))
        :unique? (or primary? (true? (:unique? o)))
        :auto? (true? (:auto? o))
        :default (:default o)
        :check (:check o)
        :references (:references o)
        :on-delete on-delete
        :on (:on o)
        :ddl col}))))

(defn- typed-column
  [table col nm declared]
  (let [opts (read-attributes table nm (nthrest col 2))]
    (if (ok? opts)
      (column-from table col nm declared opts)
      (assoc opts :table table))))

(defn parse-column
  "Parse [name type & attributes] into a column description, as a verdict."
  [table col]
  (cond
    (not (and (vector? col) (>= (count col) 2)))
    (fail :bad-column
          (str "Column must be [name type & attrs], got " (pr-str col))
          {:table table :column col})

    (not (keyword? (first col)))
    (fail :bad-column-name
          (str "Column name must be a keyword, got " (pr-str (first col)))
          {:table table :column (first col)})

    (nil? (type/parse-type (second col)))
    (fail :unknown-type
          (str "Unknown column type " (pr-str (second col)))
          {:table table :column (first col) :declared (second col)
           :known (type/known-types)})

    :else (typed-column table col (first col) (second col))))

;; ---------------------------------------------------------------------------
;; tables

(defn- duplicate-names [columns]
  (into []
        (comp (filter (fn [[_ n]] (> n 1))) (map key))
        (frequencies (map :name columns))))

(defn- table-from [name columns]
  {:name name
   :columns columns
   :pk (mapv :name (filter :primary? columns))
   :uniques (mapv :name (filter :unique? columns))
   :checks (into [] (keep (fn [c] (when (some? (:check c)) [(:name c) (:check c)])))
                 columns)
   :fks (into [] (keep (fn [c] (when (:references c)
                                 {:column (:name c)
                                  :table (:references c)
                                  :on-delete (:on-delete c)})))
              columns)})

(defn build-table
  "Validate a declaration into a table description, as a verdict.

  A declaration is {:name :person :columns [col ...]} - the shape an agent
  sends as data, and exactly what create-table's source is read into.

  Rejects a non-keyword table name, a declaration whose columns are not a
  vector of column vectors, any column that does not parse, and repeated
  column names."
  [declaration]
  (let [name (:name declaration)
        cols (:columns declaration)]
    (cond
      (not (keyword? name))
      (fail :bad-table-name "Table name must be a keyword" {:table name})

      (not (vector? cols))
      (fail :bad-table
            "Table columns must be a vector of [name type & attrs]"
            {:table name :columns cols})

      :else
      (let [parsed (mapv (fn [c] (parse-column name c)) cols)
            bad (first (remove ok? parsed))]
        (if bad
          bad
          (let [columns (mapv :value parsed)
                dupes (duplicate-names columns)]
            (if (seq dupes)
              (fail :duplicate-column
                    (str "Duplicate column(s) " (pr-str dupes) " in table " name)
                    {:table name :columns dupes})
              (succeed (table-from name columns)))))))))

(defn column
  "A table's column description, or nil."
  [table column-name]
  (some (fn [c] (when (= column-name (:name c)) c)) (:columns table)))

(defn column-names
  "The columns of a table, in declaration order."
  [table]
  (mapv :name (:columns table)))

(defn planned-table
  "The table a verdict carries, or nil when the declaration was rejected. The
  single place a caller crosses from 'checked something' to 'has a table'."
  [verdict]
  (when (ok? verdict) (:value verdict)))

;; ---------------------------------------------------------------------------
;; rows

(defn value-kind
  "The shape of a value, in the same vocabulary a column type is declared in.
  Used to say what a rejected value actually was without touching host types."
  [v]
  (cond
    (nil? v) nil
    (string? v) "text"
    (boolean? v) "bool"
    (integer? v) "int"
    (number? v) "real"
    (keyword? v) "keyword"
    :else "other"))

(defn- check-value
  "Judge one value against its column, as a verdict carrying the coerced value."
  [table c v]
  (let [nm (:name c)]
    (if (nil? v)
      (if (:required? c)
        (fail :required (str "Column " nm " of " (:name table) " is required")
              {:table (:name table) :column nm})
        (succeed nil))
      (let [verdict (type/validate (:type c) v)]
        (if (:ok verdict)
          (succeed (:value verdict))
          (if (= :too-long (:error verdict))
            (fail :too-long
                  (str "Column " nm " of " (:name table) " exceeds "
                       (:max-length verdict) " characters, got " (count v))
                  {:table (:name table) :column nm :value v
                   :max-length (:max-length verdict) :length (count v)})
            (fail :bad-type
                  (str "Column " nm " of " (:name table) " expects "
                       (:sql (:type c)) ", got " (pr-str v)
                       " (" (value-kind v) ")")
                  {:table (:name table) :column nm
                   :expected (:sql (:type c)) :value v
                   :actual-type (value-kind v)})))))))

(defn validate-row
  "Validate and coerce a row against a table description, as a verdict.

  Returns only declared columns, with defaults filled in where a column is
  absent, so the engine never sees a key the table does not have. The first
  problem found decides the verdict."
  [table row]
  (let [known (into #{} (map :name (:columns table)))
        unknown (into [] (remove known) (keys row))]
    (if (seq unknown)
      (fail :unknown-column
            (str "Unknown column(s) " (pr-str unknown) " for " (:name table))
            {:table (:name table) :column (first unknown) :columns unknown
             :known (vec known)})
      (reduce (fn [acc c]
                (if-not (ok? acc)
                  acc
                  (let [nm (:name c)
                        v (if (contains? row nm) (get row nm) (:default c))
                        checked (check-value table c v)]
                    (if (ok? checked)
                      (succeed (assoc (:value acc) nm (:value checked)))
                      checked))))
              (succeed {})
              (:columns table)))))

;; ---------------------------------------------------------------------------
;; engine constraints

(defn resolve-reference
  "Work out which column of the referenced table a foreign key points at.

  Public because the edge that inserts rows has to build the same
  {child-column parent-column} clause the constraint does; a second copy of the
  pk-default rule would be a second place to get it wrong."
  [table c catalog]
  (let [parent-name (:references c)
        parent (get catalog parent-name)]
    (cond
      (nil? parent)
      (fail :unknown-references
            (str "Column " (:name c) " of " (:name table)
                 " references unknown table " parent-name)
            {:table (:name table) :column (:name c)
             :references parent-name :known (vec (sort (keys catalog)))})

      (:on c)
      (let [on-col (column parent (:on c))]
        (if on-col
          (succeed [parent (:on c)])
          (fail :unknown-on-column
                (str "Column " (:name c) " of " (:name table)
                     " references " parent-name ", but " (:on c)
                     " is not one of its columns")
                {:table (:name table) :column (:name c)
                 :references parent-name :on (:on c)
                 :referenced-columns (column-names parent)})))

      (= 1 (count (:pk parent))) (succeed [parent (first (:pk parent))])

      :else
      (fail :ambiguous-references
            (str "Column " (:name c) " of " (:name table) " references "
                 parent-name ", which has a composite primary key "
                 (pr-str (:pk parent)) "; say which column with :on")
            {:table (:name table) :column (:name c)
             :references parent-name :pk (:pk parent)}))))

(defn- fk-op
  "The engine's :fk op for a column, or a nil-valued verdict when the column
  declares no reference. Relic's :fk option is {:cascade :delete}; DDL spells
  that :on-delete :cascade."
  [table c catalog]
  (if-not (:references c)
    (succeed nil)
    (let [resolved (resolve-reference table c catalog)]
      (if-not (ok? resolved)
        resolved
        (let [[parent ref-col-name] (:value resolved)
              ref-col (column parent ref-col-name)
              child-kind (-> c :type :kind)
              parent-kind (-> ref-col :type :kind)]
          (if (and ref-col (not= child-kind parent-kind))
            (fail :fk-type-mismatch
                  (str "Column " (:name c) " of " (:name table) " is " child-kind
                       " but references " (:name parent) "." ref-col-name
                       " which is " parent-kind)
                  {:table (:name table) :column (:name c) :type child-kind
                   :references (:name parent) :referenced-column ref-col-name
                   :referenced-type parent-kind})
            (succeed
             [:fk (:name parent)
              {(:name c) ref-col-name}
              (when (#{:cascade :delete} (:on-delete c)) {:cascade :delete})])))))))

(defn type-pred
  "The engine expression that decides a column's declared type, or nil when the
  kind has no predicate (in which case only its presence is enforced)."
  [c]
  (let [nm (:name c) {:keys [kind max-length]} (:type c)]
    (case kind
      :varchar [:and [string? nm] [<= [count nm] max-length]]
      :text [string? nm]
      :int [integer? nm]
      :real [number? nm]
      :bool [boolean? nm]
      :uuid [uuid? nm]
      :keyword [keyword? nm]
      ;; the descriptor carries the exact instance? predicate for these kinds
      (:date :timestamp) [(:pred (:type c)) nm]
      nil)))

(defn check-op
  "The engine's :check op for a column: its type predicate, and its declared
  :check form and-ed in when the declaration carries one."
  [table c]
  (let [base (type-pred c)
        declared (:check c)
        declared? (some? declared)
        ;; a nullable column must be allowed to hold nil
        pred (cond
               (and base declared?) [:and base declared]
               base base
               declared? declared)
        pred (when (some? pred)
               (if (:nullable? c) [:or [nil? (:name c)] pred] pred))]
    (when (some? pred)
      [:check {:pred pred
               :error (str "column " (:name c) " of " (:name table)
                           " must be " (:sql (:type c))
                           (when declared? ", and its :check must hold"))}])))

(defn- unique-ops [table]
  (let [pk (:pk table)
        pk-set (set pk)]
    (concat
     (for [c (:columns table)
           :when (and (:unique? c) (not (pk-set (:name c))))]
       [:unique (:name c)])
     ;; a composite key is unique as a tuple, not column by column
     (when (= 1 (count pk)) [[:unique (first pk)]])
     (when (> (count pk) 1) [(into [:unique] pk)]))))

(defn constraints
  "Translate a table description into relvar constraint operations the engine
  understands, as a verdict.

  Emits [:req ...] for non-nullable columns, [:unique ...] for unique columns
  and for the primary key (as a tuple when it is composite), one :fk op per
  reference, and one :check per column with a type predicate.

  The catalog - every known table, keyed by name - is an argument rather than
  global state, so a reference resolves against exactly the tables the caller
  means and the answer is a function of the arguments alone."
  [table catalog]
  (let [cols (:columns table)
        required (mapv :name (remove :nullable? cols))
        fks (mapv (fn [c] (fk-op table c catalog)) cols)
        bad (first (remove ok? fks))]
    (if bad
      bad
      (succeed
       (into []
             (remove nil?)
             (concat [(when (seq required) (into [:req] required))]
                     (unique-ops table)
                     (keep :value fks)
                     (keep (fn [c] (check-op table c)) cols)))))))

;; ---------------------------------------------------------------------------
;; introspection

(defn describe
  "A plain-data description of a table, safe to pr-str for an agent to read."
  [table]
  (when table
    {:table (:name table)
     :primary-key (:pk table)
     :columns (mapv (fn [c]
                      (let [d (select-keys c [:name :primary? :required? :nullable?
                                              :unique? :default :check])
                            d (assoc d :type (:sql (:type c)))]
                        (cond-> d
                          (:references c) (assoc :references (:references c))
                          (:on c) (assoc :references-column (:on c))
                          (:on-delete c) (assoc :on-delete (:on-delete c)))))
                    (:columns table))
     :references (:fks table)}))

(defn- ddl-attributes [c]
  (cond-> []
    (:primary? c) (conj :primary :key)
    (:nullable? c) (conj :nullable)
    (and (:required? c) (not (:primary? c)) (not (:nullable? c))) (conj :required)
    (and (:unique? c) (not (:primary? c))) (conj :unique)
    (:auto? c) (conj :auto)
    (some? (:default c)) (into [:default (:default c)])
    (some? (:check c)) (into [:check (:check c)])
    (:references c) (into [:references (:references c)])
    (:on c) (into [:on (:on c)])
    (:on-delete c) (into [:on-delete (:on-delete c)])))

(defn- ddl-column [c]
  (pr-str (into [(:name c) (:sql (:type c))] (ddl-attributes c))))

(defn render
  "Render a table back to the DDL form create-table accepts."
  [table]
  (when table
    (str "(create-table " (pr-str (:name table)) "\n   ["
         (str/join "\n    " (map ddl-column (:columns table))) "])")))

;; ---------------------------------------------------------------------------
;; access indexes
;; An access index is a materialized nested map over a table's columns, named
;; so the map is reachable without rebuilding its query: :order/by-customer for
;; [[:from :order] [:hash :customer]]. The primary key and unique columns
;; derive :unique indexes (aliases of the constraint's own index, so no extra
;; memory), foreign keys derive :hash indexes over the referencing column, and
;; anything else can be declared as a composite.

(def ^:private index-types #{:hash :btree})

(defn index-name
  "The derived name of the index over cols: :table/by-a+id for [:a :id]."
  [table cols]
  (keyword (str (some-> (namespace table) (str "/"))
                (name table) "/by-"
                (str/join "+" (map name cols)))))

(defn- index [table cols type origin]
  {:name (index-name table cols) :table table :cols cols :type type :origin origin})

(defn derived-indexes
  "The indexes a table's own declaration implies: a :unique index over the
  primary key, one per unique column that is not the key, and a :hash index
  per foreign key column over its referencing column.

  A column that is both unique and a reference keeps one index, the unique
  one: it subsumes what the hash would maintain (one row per key), and two
  descriptors under one name would leave the read shape undefined. A
  reference to one column of a composite key still gets its hash index -
  the key's unique index covers the tuple, not that column alone."
  [table]
  (let [pk (:pk table)
        pk-set (set pk)
        ;; columns whose single-column unique index a fk index would collide
        ;; with: a one-column key, plus unique columns outside the key.
        ;; Composite-key members are excluded - the key's unique index
        ;; covers the tuple, not any one column, and primary key implies
        ;; unique on each member, so unique? alone cannot decide this.
        overshadowed (into (if (= 1 (count pk)) #{(first pk)} #{})
                           (comp (filter :unique?) (map :name) (remove pk-set))
                           (:columns table))]
    (into []
          (concat
           (when (seq pk) [(index (:name table) pk :unique :pk)])
           (for [c (:columns table)
                 :when (and (:unique? c) (not (contains? pk-set (:name c))))]
             (index (:name table) [(:name c)] :unique :unique))
           (for [fk (:fks table)
                 :when (not (contains? overshadowed (:column fk)))]
             (index (:name table) [(:column fk)] :hash :fk))))))

(defn build-index
  "Validate a declared index over cols into a descriptor, as a verdict.

  opts may carry :type (:hash, the default, or :btree for ordered access).
  Rejects an empty or non-vector column list, unknown or repeated columns,
  an unsupported type, and a name a derived index already owns."
  [table cols opts]
  (let [declared (:type opts :hash)
        names (set (column-names table))]
    (cond
      (not (vector? cols)) (fail :bad-index "Index columns must be a vector" {:table (:name table)})
      (empty? cols) (fail :bad-index "An index needs at least one column" {:table (:name table)})
      (not (contains? index-types declared))
      (fail :unknown-index-type
            (str "Unknown index type " (pr-str declared))
            {:table (:name table) :type declared :known (vec (sort index-types))})
      :else
      (let [unknown (into [] (remove names) cols)
            dupes (into [] (comp (filter (fn [[_ n]] (> n 1))) (map key)) (frequencies cols))]
        (cond
          (seq unknown)
          (fail :unknown-column
                (str "Unknown column(s) " (pr-str unknown) " for " (:name table))
                {:table (:name table) :columns unknown :known (column-names table)})
          (seq dupes)
          (fail :duplicate-column
                (str "Duplicate column(s) " (pr-str dupes) " in index of " (:name table))
                {:table (:name table) :columns dupes})
          :else
          (let [i (index (:name table) cols declared :declared)
                derived (into #{} (map :name) (derived-indexes table))]
            (if (contains? derived (:name i))
              (fail :index-name-conflict
                    (str "Index " (:name i) " is already derived from " (:name table)
                         "'s own declaration")
                    {:table (:name table) :index (:name i) :cols cols})
              (succeed i))))))))

(defn access-query
  "The relvar query whose materialized value is an index descriptor's nested
  map. Feeding this to mat keeps the map current as the table changes, and
  rel/index on it is the map itself."
  [{:keys [table cols type]}]
  (into [[:from table]] [(into [type] cols)]))
