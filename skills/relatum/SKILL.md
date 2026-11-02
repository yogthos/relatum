---
name: relatum
description: Use when a jolt/Clojure program needs structured in-memory state that plain maps, vectors, and sets cannot guarantee — typed table schemas with unique, foreign-key, and check constraints enforced at write time, declarative queries with joins and aggregates, incrementally maintained materialized views, immutable database branching, and CSR graph algorithms (BFS, DFS, Dijkstra, shortest path, PageRank, connected components) over any two-column edge table.
---

# relatum

An in-memory relational store for jolt. A database is an immutable Clojure
map; tables are declared in DDL, rows are plain maps, and every write is
validated against the declaration before the engine sees it.

```clojure
(require '[relatum.schema :as s] '[relatum.core :as rc])

(s/create-table :person
  [[:id      :int  :primary :key]
   [:name    :text :required]
   [:balance :real :default 0.0]])

(def db (-> (rc/create-db)
            (rc/insert :person {:id 1 :name "Ada" :balance 100.0})))
```

## Why not plain maps and sets

A `#{row ...}` or vector of maps answers "give me the rows where X" only by
scanning, and nothing stops a bug from putting a string in `:id`, a second
row with the same key, or an order whose customer was never inserted.
relatum gives you, over the same plain-map rows:

- **Write-time shape enforcement.** Type, requiredness, and length violations
  reject with structured ex-data (`:relatum/error`, `:column`, `:reason`)
  before any write lands. A rejected insert leaves the database untouched.
- **Uniqueness and set semantics by index.** `:primary :key` and `:unique`
  are backed by materialized indexes; duplicate inserts are rejected (or
  handled declaratively by `:insert-or-replace`, `:insert-or-merge`,
  `:insert-ignore` via the engine).
- **Referential integrity.** `:references` rejects a child whose parent is
  missing, and `:on-delete :cascade` keeps children from outliving parents.
  Both sides of a transaction are checked at the end, so parent and child
  can be inserted in one transact in either order.
- **Declarative queries.** Filter, join, aggregate, sort without writing
  per-shape traversal code; the planner picks the index.
- **Materialized views.** `rel/mat` maintains a query incrementally as the
  database changes, so a repeated aggregate stays O(changes) instead of
  O(rows).
- **Immutable branching.** Every transact returns a new db. Branch an
  experiment with `what-if`, diff with `track-transact`, never mutate.
- **Graphs.** Any edge table becomes a CSR graph; BFS/Dijkstra/PageRank run
  over the same rows you query.

Reach for plain maps when the data is a single record or you transform once
and throw away. Reach for relatum when several kinds of rows relate to each
other and wrong shape is a bug you want caught. Row key order survives the
engine — declared-column order from an insert, written order from `:select`
(computed selections keep their written position) and `[:agg cols]` grouping —
so needing ordered output is not itself a reason to stay with plain maps.

## Declaring tables

Columns are a vector of `[name type attr...]` vectors. Types are keywords
(`:int :real :text :bool :keyword :uuid :date :timestamp`) or SQL-style
strings (`"varchar(64)"` bounds length; bare `:text` doesn't).

Attributes: `:primary :key` (required + unique, composable for a composite
key), `:required`/`:not-null`, `:nullable`, `:unique`, `:default v`,
`:check form` (engine expression like `[< :qty 100]`), `:references table`
(plus `:on column` when the target isn't the parent's primary key, and
`:on-delete :cascade`).

Without `:nullable`, every column is required. `:default` fills an absent
column. `:auto` records intent but generates nothing — supply ids yourself.
`create-table` is a macro; `create-table*` takes the same data at runtime.

```clojure
(s/create-table* :order
  [[:id      :int :primary :key]
   [:cust-id :int :references :cust]
   [:note    :text :nullable]])
```

The registry is global per jolt process: two namespaces declaring the same
table name clobber each other (last wins), so use distinct names. Inspect
with `s/table`, `s/tables`, `s/column-names`; drop with `s/forget`.

**Ordering pitfall:** `create-db` installs constraints for tables registered
*at call time*. Declare every table first, then `create-db`, or install late
arrivals with `(rc/table-constraints db :late-table)`.

## Writing

`rc/insert` / `rc/insert-many` validate and coerce (`:real` stores doubles)
against the schema, then transact. `rc/update db :table where-expr f` replaces
matching rows with `(f row)` revalidated atomically. `rc/delete db :table`
clears the table; for predicate deletes use the engine:
`(rel/transact db [:delete :table [< :age 42]])`.

A rejected write throws ex-info with `:relatum/error` and friends — read it,
don't string-match the message. Common keys: `:unknown-table`,
`:too-long`, `:unknown-column`, `:foreign-key-violation`,
`:unique-key-violation` (engine-level), `:check-violation` (engine-level).

## Reading

```clojure
(rc/q db [[:from :person]
          [:where [> :balance 50]]
          [:select :name]])
```

Operators pipeline left to right: `[:where expr...]` (comparisons are column
first: `[> :balance 50]`; ranges are `[:and [>= :a 1] [<= :a 9]]`),
`[:select ...]`, `[:join query {left-col right-col}]`, `[:left-join ...]`,
`[:agg [group-cols] [binding agg-expr]]`, `[:extend ...]`, `[:without ...]`,
`[:union q]`, `[:intersection q]`, `[:difference q]`. Sort with
`(rc/q db query {:sort [:name]})`. Rows are unordered — compare as sets.
Whole-table reads: `rc/rows`. Aggregates: `rel/sum`, `rel/avg`,
`rel/count-distinct`, `rel/max-by`, `rel/top-by`.

## Views, branching, change tracking

```clojure
(def db' (rel/mat db [[:from :person] [:agg [] [:total [rel/sum :balance]]]]))
(rel/what-if db query [:insert :person {:id 9 :name "X"}])  ; query the branch
(rel/track-transact db tx)  ; {:result db' :changes {query {:added [...] :deleted [...]}}}
```

Materialize the queries you run repeatedly; writes get slightly slower,
materialized reads become free. Views branch with the database — two transacts
off the same db share nothing.

## Graphs

Any table or query result with two related columns is a graph:

```clojure
(require '[relatum.graph :as g])
(def gr (g/graph-of db :follows))            ; or (g/graph [{:src 1 :dst 2}])
(g/neighbors gr "alice")                     ;; (["bob" 1.0])
(g/shortest-path gr "alice" "carol")         ;; {:path [...] :distance 2.0}
(g/bfs gr "alice")                           ;; ({:node n :distance d} ...)
(g/dijkstra gr "alice")                      ;; weighted distances
(g/pagerank gr :iters 30)                    ;; ranks sum to ~1
(g/connected-components gr)                  ;; undirected components
```

Node values may be any type — keywords, uuids, strings. Non-integer nodes are
dictionary-encoded internally; results come back as decoded `{:node ...}`
row maps you can insert into a table. An edge carrying `:weight` makes the
graph weighted (missing weights are 1.0). Column remap:
`(g/graph-of db :link {:src :from :dst :to :weight :w})`. A graph is a value
over a snapshot; rebuild after writes.

## Engine layer

`rc/*` is the validating surface; `relatum.engine` (`rel/q`, `rel/transact`,
`rel/mat`) is the raw ported relic engine. Use `rel/transact` for bulk or
predicate operations where per-row validation isn't needed. All modifications
must go through these — state lives in metadata, so associng the db map
directly corrupts it.

## Verification

Examples above mirror the machine-checked snippets in `README.md`, `docs/`
(checked by `jolt docs/verify.clj`, which must print `all doc examples
verified`), and the suite `jolt -M:test`. If you change schema or API
semantics, update those and run both gates.
