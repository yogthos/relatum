(ns relatum.core
  "The agent-facing surface: transacts that validate a row against its declared
  table schema before the engine ever sees it.

  The engine stores plain maps, so validation and coercion have to happen here
  on the way in. A record built with create-table is a convenient way to carry
  a validated row, but it is not the storage representation."
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

(defn- coerce [table-name row]
  (let [t (s/table table-name)]
    (when-not t
      (throw (ex-info (str "Unknown table " table-name)
                      {:relatum/error :unknown-table :table table-name
                       :known (vec (keys (s/tables)))})))
    (s/coerce-row t row)))

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

(defn insert
  "Insert a row, validated and coerced against its table schema first."
  [db table-name row]
  (let [row (coerce table-name row)]
    (check-references db table-name row)
    (rel/transact db [:insert table-name row])))

(defn insert-many [db table-name rows]
  (reduce (fn [d r] (insert d table-name r)) db rows))

(defn update
  "Update rows matching where with f, validating the new row shape. All rows
  are replaced in one transaction, so an update whose final state is valid
  succeeds even when no intermediate state would be (swapping two unique
  values), and a rejected update leaves the database untouched."
  [db table-name where f]
  (let [rows (rel/q db [[:from table-name] [:where where]])]
    ;; all deletes before all inserts: the unique constraint fires on the
    ;; incremental flow, so an interleaved delete/insert of a swapped key
    ;; would collide with a row the deletes have not reached yet
    (apply rel/transact db
           (concat (for [old rows] [:delete-exact table-name old])
                   (for [old rows] [:insert table-name (coerce table-name (f old))])))))

(defn q [db query] (rel/q db query))
(defn what-if [db query & ops] (apply rel/what-if db query ops))
(defn delete [db table-name] (rel/transact db [:delete table-name]))

(defn rows
  "Every row of a table, as maps."
  [db table-name]
  (vec (rel/q db [[:from table-name]])))
