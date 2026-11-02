# Architecture

relatum is two layers. `relatum.engine` and everything under
`src/relatum/engine/` is Dan Stone's relic, ported to jolt. The rest is the
schema layer written for this project, which turns SQL-ish DDL into the
engine's own constraint operations.

## The engine

`src/relatum/engine/` holds the port. It is close to a line-for-line
translation of relic's `impl/` namespaces, and relic's test suite came with
it, so `engine_test.clj` (110 tests) is the port's acceptance gate.

- `relatum.engine` — the public surface: `q`, `transact`, `mat`, `watch`,
  aggregates, helpers. Everything in [Queries](queries.md) and
  [Writing data](writing-data.md) bottoms out here.
- `relatum.engine.dataflow` — the incremental engine itself: graph
  construction, nodes, transaction application.
- `relatum.engine.relvar` / `expr` / `util` / `agg_index` — relvar shape,
  expression compilation, shared helpers, aggregate index trees.
- `relatum.engine.db` — the `Database` deftype that carries the dataflow
  graph next to the plain map.

The engine stores plain maps and knows nothing about the registry or about
DDL. Rows keep their key order — declared-column order on insert, written
order through `:select` and `[:agg cols]` — because projections and group rows
are built from ordered key vectors, not sets. A database is a value, and
`strip-meta` turns it back into a plain map when you want one.

## The schema layer

Five namespaces, each with one job. The split is mechanism versus edge: the
decisions are pure functions returning verdict maps, and the edges turn
verdicts into exceptions or engine ops.

- `relatum.type` — the type vocabulary. `parse-type`, `validate`,
  `conforms?`. Pure, throw-free, law-checked by writ.
- `relatum.plan` — DDL planning. A declaration goes in, a validated table
  description and its constraint ops come out, both as verdict maps. Pure.
- `relatum.schema` — the edge around plan. The global table registry, the
  ex-info policy, the `create-table` macro, coercion call sites.
- `relatum.integrity` — foreign-key decisions as pure functions over plain
  rows: `has-parent?`, `fk-verdict`, `check`, `check-verdict`.
- `relatum.core` — the agent-facing insert path. `insert` coerces, pre-checks
  references, then transacts.
- `relatum.query` — a single-arity `rows`/`insert` over the engine. It exists
  because writ's `ann` rejects multi-arity fns and the engine's `q` and
  `transact` are variadic, so a signature could not be given to them
  directly.

## Verdicts

Pure functions in this codebase return `{:ok true :value ...}` or
`{:ok false :error keyword :message string, ...}` instead of throwing. The
edge namespaces decide what a rejection means: `relatum.schema/rejected`
turns one into ex-info with `:relatum/error` in the ex-data. This is what
lets writ prove the pure namespaces total, and it is why the same rejection
can surface with either `:relatum/error` or `:relatum.engine/error`
depending on which layer threw.

## Testing and specs

- `jolt -M:test` runs the fast suite. `--all` adds the slow property tests.
- `jolt -M:specs` runs the writ specs over the five pure namespaces, exiting
  non-zero unless every report is `:ok`. The runner prints which laws were
  proved symbolically and which are only property-checked, so the gate is
  honest about the difference.

## Where relic ends and relatum begins

The engine namespaces keep relic's structure and docstrings, including relic's
error keywords (`:relatum.engine/error`) and the op vocabulary. When behavior
differs from relic it is usually a jolt constraint, not a design choice. Two
worth knowing:

- Only `:real` carries a type hint, because jolt coerces `^double` but treats
  `^long` as a non-coercing contract.
- The engine is effectful and unspec'd on purpose; writ only checks pure
  code, so the spec boundary is drawn at the pure namespaces.

If you are debugging something in the engine, relic's upstream repository and
its README remain the deeper reference for the dataflow design.
