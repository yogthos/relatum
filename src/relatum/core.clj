(ns relatum.core
  "The agent-facing surface: transacts that validate a row against its declared
  table schema before the engine ever sees it.

  The engine stores plain maps, so validation and coercion have to happen here
  on the way in. A record built with create-table is a convenient way to carry
  a validated row, but it is not the storage representation."
  (:refer-clojure :exclude [update])
  (:require [relatum.engine :as rel]
            [relatum.integrity :as integrity]
            [relatum.plan :as plan]
            [relatum.schema :as s]))

(defn create-db
  "An empty database with every registered table's constraints installed and
  every access index materialized, so reads like (get-in (index db
  :customer/by-id) [42]) are plain map lookups from the start."
  []
  (let [db (reduce (fn [db t] (rel/mat db (s/constraint-query t)))
                   {}
                   (vals (s/tables)))]
    (reduce (fn [db idx] (rel/mat db (plan/access-query idx)))
            db
            (vals (s/access-indexes)))))

(defn table-constraints
  "Install a single table's declared constraints into db."
  [db table-name]
  (rel/mat db (s/constraint-query (s/table table-name))))

(defn- index-descriptor
  [name]
  (or (get (s/access-indexes) name)
      (throw (ex-info (str "Unknown index " name)
                      {:relatum/error :unknown-index :index name
                       :known (vec (sort (keys (s/access-indexes))))}))))

(defn index
  "The nested map of an access index, by its name.

    (index db :order/by-cust-id)      ; => {1 #{row ...}, ...}
    (get-in (index db :user/by-id) [42])

  The map is the engine's own incrementally-maintained index, so reads cost
  map lookups and each transaction keeps it current. :unique indexes map key
  to row; :hash and :btree map each key to a set, nested one level per
  column. A name that was never declared or derived throws
  {:relatum/error :unknown-index}; an index that exists but holds nothing
  reads as {}.

  This is a read, never a hidden rebuild: an index this db does not keep
  throws {:relatum/error :index-not-materialized}, because a map built on
  the spot would not be maintained and would go stale on the next
  transaction. create-db installs every declared index; for one declared
  later, install it with ensure-index."
  [db name]
  (let [idx (index-descriptor name)
        q (plan/access-query idx)]
    (if (rel/materialized? db q)
      (or (rel/index db q) {})
      (throw (ex-info (str "Index " name " is not materialized in this db; "
                           "create the db after declaring it, or install it with ensure-index")
                      {:relatum/error :index-not-materialized
                       :index name})))))

(defn ensure-index
  "Install an access index into db, returning the db that keeps it current.

  Indexes declared before create-db are installed automatically; this is for
  a declaration that came later, or a db built by hand. Idempotent: a db
  that already keeps the index is returned unchanged."
  [db name]
  (rel/mat db (plan/access-query (index-descriptor name))))

(defn- table!
  "The registered table, or the :unknown-table rejection."
  [table-name]
  (or (s/table table-name)
      (throw (ex-info (str "Unknown table " table-name)
                      {:relatum/error :unknown-table :table table-name
                       :known (vec (keys (s/tables)))}))))

(defn- coerce [table-name row]
  (s/coerce-row (table! table-name) row))

(defn- fk-clauses
  "The {child-column parent-column} clause of every reference the table
  declares, with the table each one points at. A reference that cannot be
  resolved is thrown here: swallowing it would let the insert through with no
  foreign-key check at all."
  [t catalog]
  (into []
        (keep (fn [c]
                (when (:references c)
                  (let [resolved (plan/resolve-reference t c catalog)]
                    (if-not (plan/ok? resolved)
                      (throw (s/rejected resolved))
                      (let [[parent parent-column] (:value resolved)]
                        {:table (:name parent)
                         :clause {(:name c) parent-column}}))))))
        (:columns t)))

(defn- parent-pk-lookup
  "When a reference points at the parent's primary key, a [db value] -> row
  lookup, never a scan of the parent table. Reads the parent's pk index
  when this db keeps it, and falls back to a point query otherwise - the
  plain index read would throw for a db that never installed it. Nil when
  the clause is compound or the parent declares no key, so the caller falls
  back to querying the parent table."
  [table clause catalog]
  (when (= 1 (count clause))
    (let [[_child-col parent-col] (first clause)
          pk (-> (get catalog table) :pk first)]
      (when (and (= parent-col pk)
                 (contains? (s/access-indexes) (plan/index-name table [pk])))
        (let [idx-name (plan/index-name table [pk])
              q (plan/access-query (get (s/access-indexes) idx-name))]
          (fn [db v]
            (if (rel/materialized? db q)
              (get-in (rel/index db q) [v])
              (first (rel/q db [[:from table] [:where [= parent-col v]]])))))))))

(defn- check-references
  "Throw when row names a parent that is not there.

  The engine raises on the same thing when the constraint runs; doing it here
  reports it in this layer's error shape and before anything is written, so a
  rejected insert leaves the database untouched."
  [db table-name row]
  (let [catalog (s/tables)]
    (doseq [{:keys [table clause]} (fk-clauses (s/table table-name) catalog)
            :let [fast (parent-pk-lookup table clause catalog)
                  ;; the fast path hands check-verdict one synthetic parent
                  ;; holding the looked-up value: it agrees with the row on
                  ;; the clause exactly when the parent exists
                  parents (if fast
                            (let [v (get row (key (first clause)))]
                              (when-some [p (fast db v)]
                                [{(val (first clause)) v}]))
                            (vec (rel/q db [[:from table]])))
                  verdict (integrity/check-verdict clause [row] (vec parents))]
            :when (not (:ok verdict))]
      (throw (ex-info (str "Foreign key violation: " table-name " references " table)
                      {:relatum/error :foreign-key-violation
                       :table table-name
                       :references table
                       :clause clause
                       :row (:row verdict)
                       :rows (:rows verdict)})))))

(defn- relatum-data
  "The first ex-data in `e`'s cause chain that carries a relatum error key."
  [e]
  (loop [x e]
    (when x
      (let [d (ex-data x)]
        (if (or (contains? d :relatum/error) (contains? d :relatum.engine/error))
          [x d]
          (recur (ex-cause x)))))))

(defn- normalize
  "Engine rejections carry :relatum.engine/error; give them :relatum/error
  too, so a caller reads every rejection from this layer the same way. The
  engine's key and the original exception (as the cause) are kept."
  [e]
  (let [[x d] (relatum-data e)]
    (if (and d (not (contains? d :relatum/error)))
      (ex-info (ex-message x) (assoc d :relatum/error (:relatum.engine/error d)) e)
      e)))

(defmacro ^:private reporting
  "Run body, rethrowing engine rejections with the :relatum/error key."
  [& body]
  `(try ~@body
        (catch clojure.lang.ExceptionInfo e# (throw (normalize e#)))))

;; ---- keys --------------------------------------------------------------------
(defn- auto-column
  "The table's :auto integer primary key column, or nil."
  [t]
  (let [pk (:pk t)
        c (when (= 1 (count pk)) (first (filter #(= (first pk) (:name %)) (:columns t))))]
    (when (and (:auto? c) (= :int (get-in c [:type :kind]))) (:name c))))

(defn- pk-index
  "The materialized unique index on the table's primary key, or nil."
  [db t]
  (let [idx-name (plan/index-name (:name t) (:pk t))]
    (when-let [idx (get (s/access-indexes) idx-name)]
      (let [q (plan/access-query idx)]
        (when (rel/materialized? db q) (or (rel/index db q) {}))))))

(defn next-id
  "The key the next insert into `table-name` assigns when the row leaves its
  :auto integer primary key out: one more than the largest present, 1 when
  the table is empty. Throws {:relatum/error :no-auto-key} for a table
  without a single :auto integer key.

    (s/create-table :person [[:id :int :primary :key :auto] [:name :text]])
    (next-id db :person)   ; => 1"
  [db table-name]
  (let [t (table! table-name)
        col (or (auto-column t)
                (throw (ex-info (str "Table " table-name " has no single :auto integer primary key")
                                {:relatum/error :no-auto-key :table table-name})))
        ks (if-let [idx (pk-index db t)]
             (keys idx)
             (map col (rel/q db [[:from table-name]])))]
    (inc (reduce max 0 ks))))

(defn- fill-auto
  "Assign the next id to an :auto key the row leaves out."
  [db table-name row]
  (let [col (auto-column (table! table-name))]
    (if (and col (nil? (get row col)))
      (assoc row col (next-id db table-name))
      row)))

(defn row
  "The row with primary key `k` (a vector, in declared order, for a composite
  key), or nil. A lookup in the key's index when the db keeps it.

    (row db :person 1)
    (row db :enrollment [student-id course-id])"
  [db table-name k]
  (let [t (table! table-name)
        pk (:pk t)
        kv (if (= 1 (count pk)) [k] (vec k))]
    (when (empty? pk)
      (throw (ex-info (str "Table " table-name " has no primary key")
                      {:relatum/error :no-primary-key :table table-name})))
    (if-let [idx (pk-index db t)]
      (get-in idx kv)
      (first (rel/q db [[:from table-name] (into [:where] (map (fn [c v] [= c v]) pk kv))])))))

;; ---- writes ------------------------------------------------------------------
(defn insert
  "Insert a row, validated and coerced against its table schema first. An
  :auto integer key left out of the row gets next-id."
  [db table-name row]
  (reporting
    (let [row (coerce table-name (fill-auto db table-name row))]
      (check-references db table-name row)
      (rel/transact db [:insert table-name row]))))

(defn insert-many [db table-name rows]
  (reduce (fn [d r] (insert d table-name r)) db rows))

(defn update
  "Update rows matching `where` to (apply f row args), validating each new
  row against the schema, like clojure.core/update:

    (update db :person [= :id 1] assoc :balance 10.0)

  All rows are replaced in one transaction, so an update whose final state is
  valid succeeds even when no intermediate state would be (swapping two
  unique values), and a rejected update leaves the database untouched."
  [db table-name where f & args]
  (reporting
    (let [rows (rel/q db [[:from table-name] [:where where]])]
      ;; all deletes before all inserts: the unique constraint fires on the
      ;; incremental flow, so an interleaved delete/insert of a swapped key
      ;; would collide with a row the deletes have not reached yet
      (apply rel/transact db
             (concat (for [old rows] [:delete-exact table-name old])
                     (for [old rows] [:insert table-name (coerce table-name (apply f old args))]))))))

(defn q
  "Run a query; `opts` as for relatum.engine/q, e.g {:sort [:name]}."
  ([db query] (rel/q db query))
  ([db query opts] (rel/q db query opts)))

(defn what-if [db query & ops] (apply rel/what-if db query ops))

(defn delete
  "Delete the rows of `table-name` matching every `where` expression, or
  every row when none is given. Children declared :on-delete :cascade go
  with their parents.

    (delete db :person [= :id 1])
    (delete db :person [< :balance 0] [= :status :closed])
    (delete db :person)                  ; the whole table"
  [db table-name & wheres]
  (table! table-name)
  (reporting (rel/transact db (into [:delete table-name] wheres))))

(defn rows
  "Every row of a table, as maps."
  [db table-name]
  (vec (rel/q db [[:from table-name]])))

(defn- leaves [x]
  (if (map? x) (mapcat leaves (vals x)) (seq x)))

(defn children
  "The rows under `k` in a composite index, flattened in index order. Over a
  :btree index on [parent position] that reads a parent's children in
  position order:

    (s/create-index :pet [:owner-id :position] :type :btree)
    (children db :pet/by-owner-id+position 1)"
  [db index-name k]
  (vec (leaves (get (index db index-name) k))))
