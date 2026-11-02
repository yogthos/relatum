# relatum

A relational store for jolt: relic's query engine ported, plus a declarative
schema layer where you describe tables in SQL-ish DDL and the library enforces
the shape of the data (types, required columns, uniques, foreign keys, cascade
deletes).

```clojure
(require '[relatum.schema :as s] '[relatum.core :as rc])

(s/create-table :person
  [[:id      :int  :primary :key]
   [:name    "varchar(64)" :required]
   [:balance :real :default 0.0]])

(def db (-> (rc/create-db)
            (rc/insert :person {:id 1 :name "Ada" :balance 100.0})
            (rc/insert :person {:id 2 :name "Grace" :balance 500.0})
            (rc/insert :person {:id 3 :name "Linus"})))
```

A row that breaks its declaration (a string where an `:int` belongs, an
over-long name, a missing required column) is rejected before the engine sees
it, with ex-data saying which column and why. `s/create-table*` does the same
at runtime for schemas loaded from data.

## Querying

A query is a vector of operations over the database, and `rc/q` returns the
matching rows:

```clojure
(set (rc/rows db :person))
;; => #{{:id 1, :name "Ada", :balance 100.0}
;;     {:id 2, :name "Grace", :balance 500.0}
;;     {:id 3, :name "Linus", :balance 0.0}}

(rc/q db [[:from :person] [:where [= :name "Ada"]]])
;; => ({:id 1, :name "Ada", :balance 100.0})

;; operations pipeline left to right: filter, then keep the named columns
(set (rc/q db [[:from :person]
               [:where [> :balance 50]]
               [:select :name]]))
;; => #{{:name "Ada"} {:name "Grace"}}
```

Rows come back in no particular order — a relation is a set. Expressions
compose (`[:and ...]`, `[:or ...]`, `[:not ...]`) and comparisons chain like
their Clojure counterparts, with the column first: `[<= :balance 100]` asks
`balance <= 100`. Two constants chain, they don't make a range —
`[<= :balance 100 500]` means `balance <= 100 <= 500`. To keep rows inside an
interval, spell it `[:and [>= :balance 100] [<= :balance 500]]`.

```clojure
;; [:agg [group-cols] bindings] — group by nothing for a whole-table total
(rc/q db [[:from :person] [:agg [] [:n count]]])
;; => ({:n 3})
```

Joins, the remaining aggregates (`rel/sum`, `rel/avg`, ...), sorting, and
sub-selects are the same query language with `relatum.engine` aliased —
[docs/queries.md](docs/queries.md) covers them.

## Graphs

Any table (or query result) whose rows carry two related columns becomes a
graph: pass the table name or a full query to `graph-of`, or edge maps
directly to `graph`. Node values can be anything — keywords, uuids, strings,
numbers — non-integer values are dictionary-encoded to dense ids in
first-seen order, and everything comes back decoded:

```clojure
(require '[relatum.graph :as g])

(s/create-table :follows
  [[:src :text]
   [:dst :text]])

(def db (-> (rc/create-db)
            (rc/insert :follows {:src "alice" :dst "bob"})
            (rc/insert :follows {:src "bob" :dst "carol"})
            (rc/insert :follows {:src "carol" :dst "alice"})))

(def follow-graph (g/graph-of db :follows))

(g/neighbors follow-graph "alice")   ;; => (["bob" 1.0])
(g/degree follow-graph "alice")      ;; => 1
(g/in-degree follow-graph "carol")   ;; => 1
```

Traversal and analysis return rows keyed by `:node`, so results can be
transacted straight back into the database:

```clojure
(set (g/bfs follow-graph "alice"))
;; => #{{:node "alice", :distance 0} {:node "bob", :distance 1}
;;     {:node "carol", :distance 2}}

(g/shortest-path follow-graph "alice" "carol")
;; => {:path ["alice" "bob" "carol"], :distance 2.0}
```

`dfs` gives preorder visitation indices, `dijkstra` weighted distances, and
`pagerank`/`connected-components` rank each node and label the undirected
components. An edge with a `:weight` (any positive number) makes the graph
weighted; edges without one default to weight 1.0.

```clojure
(def weighted (g/graph [{:src :a :dst :b :weight 1.0}
                        {:src :b :dst :c :weight 1.0}
                        {:src :a :dst :c :weight 5.0}]))

(g/shortest-path weighted :a :c)
;; => {:path [:a :b :c], :distance 2.0}

(g/pagerank weighted :iters 30)
;; => ({:node :a, :rank ...} ...)

(g/connected-components weighted)
;; => ({:node :a, :component :a} {:node :b, :component :a} {:node :c, :component :a})
```

Layout is CSR (compressed sparse row) — forward and reverse adjacency in
flat long arrays, so a build is O(V+E)
and every algorithm is a tight loop over primitive arrays. See
[docs/graphs.md](docs/graphs.md).

## Performance

`bench/bench.clj` measures the engine against plain maps, vectors and sets at
10,000 rows (`jolt bench/bench.clj` to run it). The guidance those numbers
support:

- Materialize the queries you run repeatedly. `rel/mat` installs a query's
  dataflow into the database; after that a read doesn't re-plan, and every
  write updates the result incrementally. You pay a little on each write and
  save the whole computation on each read — a materialized aggregate answers
  hundreds of times faster than reducing over the rows on every call, a
  materialized join several times faster than rebuilding it with `map`.

  ```clojure
  (require '[relatum.engine :as rel])

  (def db (rel/mat db [[:from :person] [:agg [] [:total [rel/sum :balance]]]]))

  (rc/q db [[:from :person] [:agg [] [:total [rel/sum :balance]]]])
  ;; => ({:total 600.0})

  ;; the total follows the write — no re-plan, no re-computation of old rows
  (rc/q (rc/insert db :person {:id 4 :name "Alan" :balance 25.0})
        [[:from :person] [:agg [] [:total [rel/sum :balance]]]])
  ;; => ({:total 625.0})
  ```

- Don't put a cold query in a hot loop. A query you never materialized is
  planned again on every call, roughly 9–90× the plain equivalent at this
  size. Fine for one-offs and exploration.
- Give point lookups an index. Through a materialized `[:unique ...]`, a
  lookup by id is about twice as fast as a linear scan of the table — but a
  plain map lookup is still far cheaper, so when you need that last mile,
  keep a plain map next to the database.
- `rc/insert` validates the row against the schema, roughly six times the
  cost of a bare engine insert. For bulk loads of data you already trust,
  `rel/transact` with a map of table to rows is cheaper.

None of this needed engine tuning; the costs sit where you'd expect them —
planning on cold reads, index maintenance on writes.

## Updating and deleting

```clojure
(def db (rc/update db :person [= :id 3] (fn [row] (assoc row :balance 25.0))))
(def db (rc/delete db :person))   ; every row of the table
```

`rc/update` takes a function of the old row and validates what it returns
against the schema. The database is a value: every write returns a new one,
and a rejected write throws, leaving the old database untouched.

## Immutability

The database is a persistent value built on Clojure's ordinary immutable
structures. There is no global store of rows anywhere: the engine namespace
holds no state, and the dataflow graph a materialized query maintains rides
inside the database value itself.

Every write returns a new database and leaves every existing one alone, so
branching just works:

```clojure
(def db (rc/insert db :person {:id 4 :name "Alan" :balance 25.0}))
(def db2 (rc/insert db :person {:id 5 :name "Radia" :balance 900.0}))

(rc/q db [[:from :person] [:agg [] [:n count]]])
;; => ({:n 4})    ; the write that made db2 left db alone

(rc/q db2 [[:from :person] [:agg [] [:n count]]])
;; => ({:n 5})
```

Materialized views follow the same rule. They are part of the database value,
so two transactions branching from one materialized database each maintain
their own results, and the original keeps answering with what it had.

The only process-global state is the table registry. `s/create-table`
registers declarations in a process-wide atom, because a foreign key names a
table and something has to bind that name. That is metadata, not data — rows
and query results only ever travel through the database value. `rc/create-db`
reads the registry once when it installs constraints; registering another
table later changes an existing database not at all until you extend it with
`rc/table-constraints`.

Full API documentation, with verified examples, lives in [docs/](docs/) —
tables, types, queries, writing data, constraints, and the architecture. Every
example there is checked by `jolt docs/verify.clj`.

## Running things

- `jolt -M:test` — the fast suite. `jolt -M:test --all` adds the 10k-trial
  property tests, which take a long time.
- `jolt -M:specs` — the writ spec over `relatum.type`, exiting non-zero if
  writ's report is not `:ok`.
- `jolt bench/bench.clj` — the engine against plain data structures at 10k
  rows, printing per-op timings and the ratios.

Note on `:specs`: it is a spec gate, not a proof gate. writ currently proves
one law symbolically; the rest are property-checked against generated inputs,
and the runner prints the split along with a reminder that a tested law holds
for the inputs tried, not for all inputs.

## License

MIT. `src/relatum/engine/` is ported from relic (MIT, Dan Stone / wotbrew);
relic's notice is reproduced verbatim in `LICENSE`.
