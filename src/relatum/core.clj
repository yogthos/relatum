(ns relatum.core
  "The agent-facing surface: transacts that validate a row against its declared
  table schema before the engine ever sees it.

  The engine stores plain maps, so validation and coercion have to happen here
  on the way in. A record built with create-table is a convenient way to carry
  a validated row, but it is not the storage representation."
  (:refer-clojure :exclude [update])
  (:require [relatum.engine :as rel]
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

;; fk-clauses re-resolves every reference through the catalog; a batch (or a
;; loop of single inserts) asks the same question for every row, so the
;; answer is kept against the catalog and access-index values it was computed
;; from. Any table or index registration replaces those values, which retires
;; the cache.
(defonce ^:private fk-cache (volatile! {:catalog nil :indexes nil :by-table {}}))

(defn- fk-plan
  "A resolved reference with what checking it needs, worked out once: its
  child and parent columns, and the access query of the parent's index over
  the referenced column when it is a single column that has one."
  [{:keys [table clause] :as fk} indexes]
  (let [child-cols (vec (keys clause))
        parent-cols (mapv clause child-cols)
        idx (when (= 1 (count parent-cols))
              (get indexes (plan/index-name table parent-cols)))]
    (assoc fk
           :child-cols child-cols
           :parent-cols parent-cols
           :parent-q (when (and idx (= parent-cols (:cols idx)))
                       (plan/access-query idx)))))

(defn- table-fks
  "The table's references as fk-plans, memoized against the catalog and the
  access indexes."
  [t catalog]
  (let [indexes (s/access-indexes)
        c @fk-cache
        fresh? (and (identical? catalog (:catalog c)) (identical? indexes (:indexes c)))]
    (if-some [v (when fresh? (get (:by-table c) (:name t)))]
      v
      (let [v (mapv #(fk-plan % indexes) (fk-clauses t catalog))]
        (vreset! fk-cache (if fresh?
                            (assoc-in c [:by-table (:name t)] v)
                            {:catalog catalog :indexes indexes :by-table {(:name t) v}}))
        v))))

(defn- key-fn
  "The row's value for one column, or the vector of its values for several."
  [cols]
  (if (= 1 (count cols))
    (let [c (first cols)] (fn [row] (get row c)))
    (fn [row] (mapv #(get row %) cols))))

(defn- indexed-presence
  "When db keeps the access index q, a v -> boolean test for a row under
  key v in it; else nil."
  [db q]
  (when (and q (rel/materialized? db q))
    (let [m (or (rel/index db q) {})]
      ;; :unique maps v to a row, :hash/:btree to a set of rows
      (fn [v] (boolean (seq (get m v)))))))

(defn- parent-test
  "A child-row -> boolean test that the reference `fk` resolves, against the
  parents already in db plus `pending`, the rows of the parent table being
  written in the same transaction. A parent agrees with a child on every
  column of the clause, nil included, as relatum.integrity/same-key? has
  it. Each parent is looked up in its index when db keeps one; otherwise the
  parent table is read once, on the first miss, not once per row."
  [db {:keys [table child-cols parent-cols parent-q]} pending]
  (let [pkey (key-fn parent-cols)
        ckey (key-fn child-cols)
        batch (into #{} (map pkey) pending)
        in-db (or (indexed-presence db parent-q)
                  (let [present (delay (into #{} (map pkey) (rel/q db [[:from table]])))]
                    (fn [k] (contains? @present k))))]
    (fn [row]
      (let [k (ckey row)]
        (or (contains? batch k) (in-db k))))))

(defn- check-references
  "Throw when a row names a parent that is not there, neither in db nor
  among `pending` ({table rows}), the rows written in the same transaction.

  The engine raises on the same thing when the constraint runs; doing it here
  reports it in this layer's error shape and before anything is written, so a
  rejected write leaves the database untouched."
  [db table-name rows pending]
  (doseq [{:keys [table clause] :as fk} (table-fks (table! table-name) (s/tables))
          :let [ok? (parent-test db fk (get pending table))
                bad (into [] (remove ok?) rows)]
          :when (seq bad)]
    (throw (ex-info (str "Foreign key violation: " table-name " references " table)
                    {:relatum/error :foreign-key-violation
                     :table table-name
                     :references table
                     :clause clause
                     :row (first bad)
                     :rows bad}))))

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

(defn- prepare
  "The rows validated and coerced against the table, in order. An :auto key a
  row leaves out gets the next id, from one counter per call: it starts at
  next-id and steps past every key the batch has used so far, explicit or
  assigned - the ids one insert per row would hand out, without rescanning
  the key index for each row."
  [db table-name rows]
  (let [t (table! table-name)
        col (auto-column t)]
    (if-not col
      (mapv #(s/coerce-row t %) rows)
      (let [start (delay (next-id db table-name))]
        (loop [rows (seq rows) floor 0 out (transient [])]
          (if-not rows
            (persistent! out)
            (let [r (first rows)
                  r (if (nil? (get r col)) (assoc r col (max @start floor)) r)
                  r (s/coerce-row t r)]
              (recur (next rows) (max floor (inc (get r col))) (conj! out r)))))))))

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

;; ---- reads by column ---------------------------------------------------------
;; access indexes grouped by [table leading-column], best first, kept against
;; the access-index map they came from (a new map means the schema changed)
(defonce ^:private led-by-cache (volatile! {:indexes nil :by-col {}}))

(defn- indexes-led-by
  "The access indexes over `table` whose first column is `col`: single-column
  ones before composites, :unique before :hash/:btree. Each carries its
  access query under ::q, built once, so checking it is materialized hashes
  a vector whose hash is already cached."
  [table col]
  (let [all (s/access-indexes)
        c @led-by-cache]
    (get (if (identical? all (:indexes c))
           (:by-col c)
           (let [by (reduce-kv (fn [m k is]
                                 (assoc m k (->> is
                                                 (sort-by (fn [i] [(count (:cols i))
                                                                   (if (= :unique (:type i)) 0 1)]))
                                                 (mapv #(assoc % ::q (plan/access-query %))))))
                               {}
                               (group-by (fn [i] [(:table i) (first (:cols i))]) (vals all)))]
             (vreset! led-by-cache {:indexes all :by-col by})
             by))
         [table col])))

(defn- rows-under
  "The set of rows below one key of an index: `depth` more levels of
  nesting, whose leaves are a row (:unique) or a set of rows (:hash,
  :btree). A single-level hash or btree hit is the index's own set."
  [m depth unique?]
  (cond
    (nil? m) #{}
    (zero? depth) (if unique? #{m} m)
    :else (into #{} (mapcat #(rows-under % (dec depth) unique?)) (vals m))))

(defn lookup
  "The set of rows of `table-name` whose column `col` equals `v`.

    (lookup db :order :cust-id 42)   ; => #{{:id 7 :cust-id 42 ...} ...}

  Reads the first materialized access index led by that column - the
  derived index of a primary key, unique column or foreign key, or a
  declared one such as [:cust-id :status] - so it costs a map lookup and
  builds no plan. Without such an index (or for a nil v) it falls back to
  (q db [[:from table-name] [:where [= col v]]]): the same rows, the slow
  way. Throws {:relatum/error :unknown-table} or {:relatum/error
  :unknown-column} for names the schema does not declare."
  [db table-name col v]
  (let [t (table! table-name)]
    (when-not (plan/column t col)
      (throw (ex-info (str "Unknown column " col " for " table-name)
                      {:relatum/error :unknown-column :table table-name :column col
                       :known (plan/column-names t)})))
    (or (when (some? v)
          (some (fn [idx]
                  (let [q (::q idx)]
                    (when (rel/materialized? db q)
                      (rows-under (get (rel/index db q) v)
                                  (dec (count (:cols idx)))
                                  (= :unique (:type idx))))))
                (indexes-led-by table-name col)))
        (set (rel/q db [[:from table-name] [:where [= col v]]])))))

;; ---- writes ------------------------------------------------------------------
(defn- parents-first
  "The table names reordered so each comes after the tables it references
  among them. A reference cycle keeps the given order for the tables
  caught in it."
  [order]
  (let [catalog (s/tables)
        present (set order)
        deps (into {} (map (fn [t]
                             [t (into #{} (comp (map :table)
                                                (filter present)
                                                (remove #{t}))
                                      (table-fks (table! t) catalog))]))
                   order)]
    (loop [todo order done #{} out []]
      (if (empty? todo)
        out
        (let [ready (or (first (filter #(every? done (deps %)) todo))
                        (first todo))]
          (recur (remove #{ready} todo) (conj done ready) (conj out ready)))))))

(defn insert-tables
  "Insert rows into several tables in one transaction: `tables` is a map of
  table name to rows, or a sequence of [table-name rows] pairs.

    (insert-tables db {:customer [{:id 1 :name \"Ada\"}]
                       :order    [{:id 10 :cust-id 1}]})

  Every row is validated and coerced against its table (defaults filled,
  :auto keys assigned as insert-many does), every foreign key is checked
  against the db plus the rows being written, and only then do all rows go
  to the engine as a single transaction. Table order does not matter:
  constraints are checked at the end of the transaction, and the tables
  are written parents first (by their foreign keys) whatever order they
  are given in. A rejected load throws with :relatum/error and
  leaves the database untouched.

  This is the bulk-load path: one validation pass and one transaction for
  the whole load, rather than one per row."
  [db tables]
  (reporting
    (let [;; gather each table's rows in the order given, so a table named
          ;; twice draws its :auto ids from one counter
          [order by-table] (reduce (fn [[order m] [t rows]]
                                     [(if (contains? m t) order (conj order t))
                                      (assoc m t (into (get m t []) rows))])
                                   [[] {}]
                                   tables)]
      (if (every? empty? (vals by-table))
        db
        (let [prepared (reduce (fn [m t] (assoc m t (prepare db t (get by-table t))))
                               {} order)]
          (doseq [t order]
            (check-references db t (get prepared t) prepared))
          ;; parents before children: the engine accepts either order, but
          ;; a child written first parks as a pending violation until its
          ;; parent arrives, which costs more than checking it on arrival
          (if (= 1 (count order))
            (rel/transact db prepared)
            (apply rel/transact db (for [t (parents-first order)]
                                     {t (get prepared t)}))))))))

(defn insert
  "Insert a row, validated and coerced against its table schema first. An
  :auto integer key left out of the row gets next-id."
  [db table-name row]
  (insert-tables db [[table-name [row]]]))

(defn insert-many
  "Insert rows into one table: each validated and coerced like insert, then
  all of them written in a single transaction, so a rejected row leaves the
  database untouched. :auto keys left out are assigned in row order, as
  successive inserts would assign them. Rows may reference rows earlier or
  later in the same batch. See insert-tables to load several tables at once."
  [db table-name rows]
  (insert-tables db [[table-name rows]]))

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
