# relatum docs

relatum is a relational store for jolt. You describe tables in SQL-ish DDL,
and the library enforces the shape of the data. Underneath sits a port of
relic's incremental dataflow engine, so queries are values and the database is
a plain immutable map.

- [Tables](tables.md) — declaring tables, column attributes, the registry
- [Types](types.md) — the type vocabulary and how values are coerced
- [Queries](queries.md) — the query language, expressions, aggregation
- [Writing data](writing-data.md) — inserts, updates, deletes, transactions
- [Constraints](constraints.md) — what a declaration enforces, foreign keys,
  cascade deletes, error shapes
- [Architecture](architecture.md) — how the namespaces fit together, what came
  from relic

The short version, to orient you:

```clojure
(require '[relatum.schema :as s]
         '[relatum.core :as rc])

(s/create-table :person
  [[:id      :int         :primary :key]
   [:name    "varchar(64)" :required]
   [:balance :real         :default 0.0]])

(def db (rc/create-db))
(def db (rc/insert db :person {:id 1 :name "Ada"}))

(rc/q db [[:from :person] [:where [> :balance 100]]])
```

A row that breaks its declaration is rejected before the engine sees it, with
ex-data naming the column and the reason.
