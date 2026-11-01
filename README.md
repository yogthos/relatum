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

(def db (rc/create-db))
(def db (rc/insert db :person {:id 1 :name "Ada"}))
```

A row that breaks its declaration (a string where an `:int` belongs, an
over-long name, a missing required column) is rejected before the engine sees
it, with ex-data saying which column and why. `s/create-table*` does the same
at runtime for schemas loaded from data.

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
