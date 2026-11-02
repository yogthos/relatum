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

## Updating and deleting

```clojure
(def db (rc/update db :person [= :id 3] (fn [row] (assoc row :balance 25.0))))
(def db (rc/delete db :person))   ; every row of the table
```

`rc/update` takes a function of the old row and validates what it returns
against the schema. The database is a value: every write returns a new one,
and a rejected write throws, leaving the old database untouched.

Full API documentation, with verified examples, lives in [docs/](docs/) —
tables, types, queries, writing data, constraints, and the architecture. Every
example there is checked by `jolt docs/verify.clj`.

## Running things

- `jolt -M:test` — the fast suite. `jolt -M:test --all` adds the 10k-trial
  property tests, which take a long time.
- `jolt -M:specs` — the writ spec over `relatum.type`, exiting non-zero if
  writ's report is not `:ok`.

Note on `:specs`: it is a spec gate, not a proof gate. writ currently proves
one law symbolically; the rest are property-checked against generated inputs,
and the runner prints the split along with a reminder that a tested law holds
for the inputs tried, not for all inputs.

## License

MIT. `src/relatum/engine/` is ported from relic (MIT, Dan Stone / wotbrew);
relic's notice is reproduced verbatim in `LICENSE`.
