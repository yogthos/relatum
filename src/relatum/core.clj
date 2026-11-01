(ns relatum.core
  "The agent-facing surface: transacts that validate a row against its declared
  table schema before the engine ever sees it.

  The engine stores plain maps, so validation and coercion have to happen here
  on the way in. A record built with create-table is a convenient way to carry
  a validated row, but it is not the storage representation."
  (:require [relatum.engine :as rel]
            [relatum.schema :as s]))

(defn create-db
  "An empty database with every registered table's constraints installed."
  []
  (reduce (fn [db t] (rel/mat db (s/constraint-query t)))
          {}
          (vals (s/tables))))

(defn table-constraints
  "Install a single table's declared constraints into db."
  [db table-name]
  (rel/mat db (s/constraint-query (s/table table-name))))

(defn- coerce [table-name row]
  (let [t (s/table table-name)]
    (when-not t
      (throw (ex-info (str "Unknown table " table-name)
                      {:relatum/error :unknown-table :table table-name
                       :known (vec (keys (s/tables)))})))
    (s/coerce-row t row)))

(defn insert
  "Insert a row, validated and coerced against its table schema first."
  [db table-name row]
  (rel/transact db [:insert table-name (coerce table-name row)]))

(defn insert-many [db table-name rows]
  (reduce (fn [d r] (insert d table-name r)) db rows))

(defn update
  "Update rows matching where with f, validating the new row shape."
  [db table-name where f]
  (let [t (s/table table-name)
        rows (rel/q db [[:from table-name] [:where where]])]
    (reduce (fn [d old]
              (let [new (coerce table-name (f old))]
                (rel/transact d [:delete-exact table-name old]
                                [:insert table-name new])))
            db
            rows)))

(defn q [db query] (rel/q db query))
(defn what-if [db query & ops] (apply rel/what-if db query ops))
(defn delete [db table-name] (rel/transact db [:delete table-name]))

(defn rows
  "Every row of a table, as maps."
  [db table-name]
  (vec (rel/q db [[:from table-name]])))
