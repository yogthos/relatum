# Queries

A query is a vector of operations over a database, and the database is a plain
map. `q` returns a seq of rows.

```clojure
(require '[relatum.core :as rc]
         '[relatum.engine :as rel])
```

`rc/q` is `rel/q`. The engine namespace is where the full surface lives, so
alias both if you use the aggregates and helpers.

```clojure
(rc/q db [[:from :person] [:where [= :name "Ada"]]])
```

## Operations

A query pipelines operations left to right. Each one is a vector.

- `[:from table]` — the rows of a table.
- `[:where & exprs]` — keep rows satisfying every expression.
- `[:select & cols]` — keep only the named columns. Columns come back in the
  order you selected them, at any width.
- `[:without & cols]` — drop the named columns.
- `[:extend & [col expr]]` — add a column computed from the row.
- `[:rename {old new}]` — rename columns.
- `[:join query {left-col right-col}]` — inner join on the clause.
- `[:left-join query {left-col right-col}]` — keep left rows without a match.
- `[:union query]`, `[:intersection query]`, `[:difference query]` — set
  operations.
- `[:const rows]` — a literal relation, handy for trying the algebra out:
  `(rel/q {} [[:const [{:a 1} {:a 2}]] [:where [> :a 1]]])`.
- `[:qualify "ns"]` — namespace every column keyword, `:a` becomes `:ns/a`.

```clojure
(rel/q db
  [[:from :order]
   [:join [[:from :customer]] {:cust-id :id}]
   [:where [> :total 100]]
   [:select :id :name]])
```

The join takes a clause mapping left columns to right columns, and merges the
matching rows.

Rows are plain maps, and jolt maps keep insertion order, so a row's keys stay
in the order they were associated — declared-column order from an insert, and
selection order from `:select`, `[:agg cols]` grouping, and joins. A computed
selection keeps the position where you wrote it, and a multi-key computed
binding keeps the binding's own order. Only `[:without ...]` keeps the source
order minus the dropped keys.

```clojure
(keys (first (rel/q db [[:from :sale] [:select :amt :dept]])))
;; => (:amt :dept)

(keys (first (rel/q db [[:from :sale] [:select :dept [:double [* :amt 2]] :amt]])))
;; => (:dept :double :amt)
```

## Expressions

Inside `:where` and `:extend`, a keyword is a column lookup and a vector is a
call. The operator can be a function or a recognized symbol:

```clojure
[= :name "Ada"]          ;; equality
[> :balance 100]         ;; ordered comparison, also < <= >=
[:and [= :a 1] [> :b 2]] ;; boolean composition, also :or :not
[+ :qty :qty]            ;; arithmetic
[inc :n]                 ;; any clojure fn in prefix position
[str :first " " :last]   ;; strings
```

Comparisons are `compare`-based, so dates, strings, keywords and vectors all
order, not just numbers. They chain like the Clojure versions, with the
column first: `[<= :start :end]` asks `start <= end`, and a range is spelled
`[:and [>= :a 1] [<= :a 3]]`. `nil` and `false` are falsey, everything else is
truthy.

## Aggregation

`[:agg [group-cols] [binding agg-expr]]` groups rows and computes one output
per group. Group by nothing for a grand total:

```clojure
(rel/q db [[:from :sale] [:agg [] [:total [rel/sum :amt]]]])
;; => ({:total 10})
```

Group by a column to get a row per value:

```clojure
(rel/q db
  [[:from :sale]
   [:agg [:dept]
         [:total [rel/sum :amt]]
         [:best  [rel/min-by :amt]]
         [:n     count]]])
;; => ({:dept "food", :n 1, :total 2, :best {:dept "food", :amt 2}}
;;     {:dept "toys", :n 2, :total 8, :best {:dept "toys", :amt 3}})
```

`count` and `count-distinct` are aggregate expressions themselves; the rest
are functions in `relatum.engine` that build one:

- `rel/sum`, `rel/avg` — totals and means over an expression.
- `rel/min-by`, `rel/max-by` — the whole row with the smallest or largest
  value.
- `rel/top n expr`, `rel/bottom n expr` — the n highest or lowest values.
- `rel/top-by n expr`, `rel/bottom-by n expr` — the n rows for those values.
- `rel/any expr`, `rel/not-any expr` — whether any row in the group is truthy.
- `rel/count-distinct expr`, `rel/set-concat expr` — distinct count, or every
  value as a set.

## Options map

`q` takes a third argument: `{:sort :rsort :xf :into}`.

```clojure
(rel/q db [[:from :person]] {:sort [:balance]})
(rel/q db [[:from :person]] {:rsort [:balance]})   ;; descending
(rel/q db [[:from :person]] {:xf (map :name) :into []})
;; => ["Ada" "Grace"]
```

`:sort` takes an expression or a vector of them, so `[:a :b]` sorts by `:a`
then `:b`.

## Sub-selects and env

`rel/sel` and `rel/sel1` embed a query inside an expression, binding all
matching rows as a set, or the first one:

```clojure
(rel/q db [[:from :customer]
           [:extend [:first-order [rel/sel1 :order {:id :cust-id}]]]])
;; => ({:id 7, :name "ada", :first-order {:id 1, :cust-id 7, :total 3}})
```

The clause maps the outer row's columns to the sub-query's columns.

`rel/env` reads a parameter from the database environment, set with
`rel/with-env`:

```clojure
(rel/q (rel/with-env db {:now 99})
       [[:from :customer] [:extend [:now [rel/env :now]]]])
```

These two use sentinels rather than keywords on purpose, so untrusted edn
can't exfiltrate data through them.

## Single-row helpers

```clojure
(rel/row db [[:from :customer]] [= :id 7])   ;; first matching row, or nil
(rel/exists? db [[:from :customer]] [= :name "ada"])  ;; => true
```

## Trying a change without committing

`what-if` runs the query against the database a transaction would produce:

```clojure
(rel/what-if db [[:from :customer]]
             [:insert :customer {:id 8 :name "grace"}])
```

## Nested access

A query answers a question, but when the access path is known up front you
can read the database like a nested map instead, with a cost you can see:
every hop is a map lookup, so nothing scans a table and there is no plan to
ask about.

For a declared table, the primary key, unique columns, and foreign keys each
maintain a nested map incrementally under a name derived from the
declaration — `:customer/by-id`, `:order/by-cust-id`, or
`:order/by-cust-id+status` for a composite you declare yourself:

```clojure
(require '[relatum.core :as rc])

(s/create-index :order [:cust-id :status])

(get-in (rc/index db :customer/by-id) [42])          ;; the row, or nil
(get (rc/index db :order/by-cust-id) 42)             ;; #{order ...}
(take 10 (get-in (rc/index db :order/by-cust-id+status) [42 :shipped]))
```

`rc/index` returns the plain nested map; after that one call it is ordinary
Clojure data. A `:unique` index maps key to row, a `:hash` or `:btree`
index maps each key to a set of rows, nested one level per indexed column.
`:btree` sorts each level, so range reads work:

```clojure
(subseq (rc/index db :order/by-created) >= 1000)
```

The maps are the engine's own materialized indexes, kept current by every
transaction, so a read never rebuilds anything. The primary-key index is the
same map the unique constraint already maintains — it costs nothing extra.
Composite indexes and the per-foreign-key maps are additional materialized
state: cheaper reads, proportionally more memory, and slightly slower
writes — the same trade a database index makes.

A column that is both unique and a reference keeps one index — the unique
one. A reference to one column of a composite parent key still gets its
hash index, since the key's unique index covers the tuple, not that column
alone.

An index name that was never declared or derived is rejected up front with
`{:relatum/error :unknown-index}` and the list of known names. Reading an
index the db does not keep — one declared after the db was created — is
rejected with `{:relatum/error :index-not-materialized}` rather than
quietly rebuilding it: a rebuilt map would not be maintained and would go
stale on the next transaction. Declare indexes before `create-db`, or
install one later and thread the db it returns:

```clojure
(def db' (rc/ensure-index db :order/by-status))
```

## The schema-aware surface

`relatum.query` is a thin single-arity wrapper over the same engine, which
exists so the writ specs can give it a signature:

```clojure
(require '[relatum.query :as query])
(query/rows db [[:from :qt] [:where [= :id 1]]])
(query/insert db [:insert :qt {:id 1 :name "ada"}])
```

For declared tables you will mostly use `relatum.core` instead, which
validates against the schema. That is the next page.
