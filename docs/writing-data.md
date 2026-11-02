# Writing data

The database is a value. Every write returns a new database and leaves the old
one alone, so there is no rollback to worry about, and a rejected write
returns nothing because it threw first.

```clojure
(require '[relatum.core :as rc])
```

## Building a database

`create-db` returns an empty database with every registered table's
constraints installed. Declare your tables first:

```clojure
(def db (rc/create-db))
```

If you register another table later, install just that one's constraints:

```clojure
(def db (rc/table-constraints db :order))
```

## Inserting

`rc/insert` validates the row against the table schema, checks its foreign
keys, and only then hands it to the engine:

```clojure
(def db (rc/insert db :person {:id 1 :name "Ada"}))
(def db (rc/insert db :person {:id 2 :name "Grace" :balance 500.0}))

(rc/rows db :person)
;; => [{:id 1, :name "Ada", :balance 0.0}
;;     {:id 2, :name "Grace", :balance 500.0}]
```

The row that goes in is not necessarily the row you passed: columns left out
get their defaults, `:real` values are doubled, and unknown columns reject.
The engine itself is schema-blind, so bypassing `rc/insert` and talking to
`rel/transact` directly skips the validation. That is sometimes what you
want, and the [engine](#the-engine-underneath) section says what you trade.

Several rows at once:

```clojure
(def db (rc/insert-many db :person [{:id 3 :name "Linus"}]))
```

## Updating

`rc/update` takes a where-expression and a function of the old row. It is
read, then delete and re-insert, so the new row shape is validated against
the schema too:

```clojure
(def db (rc/update db :person [= :id 1] (fn [row] (assoc row :balance 10.0))))
```

The engine's own `[:update table fn-or-map exprs]` command (below) also takes
a map like `{:balance inc}`, but `rc/update` does not, so stick to functions
here.

All matching rows are replaced in one transaction. An update whose final
state is valid therefore succeeds even when no intermediate state would be,
so swapping the unique values of two rows works, and a rejected update leaves
the database untouched.

## Deleting

```clojure
(rc/delete db :person)                      ;; every row of the table
```

With foreign keys declared `:on-delete :cascade`, deleting a parent's rows
this way takes the children with it. For deleting selected rows, use the
engine's transaction commands below.

## The engine underneath

`relatum.engine` has a transaction command vocabulary that predates the
schema layer, and it all still works. `rel/transact` takes a database and any
number of commands, applying them as one transaction. If a constraint fails,
the whole transaction throws and you keep your old database.

```clojure
(require '[relatum.engine :as rel])

(def db (rel/transact db
          [:insert :person {:id 4 :name "Alan"}]
          [:insert :person {:id 5 :name "Kay"}]))
```

Commands:

- `[:insert table row1 row2 ...]` — add rows.
- `[:delete-exact table row1 row2 ...]` — remove rows by exact match.
- `[:delete table expr1 expr2 ...]` — remove rows matching the expressions,
  e.g `[:delete :person [< :balance 0]]`.
- `[:update table fn-or-map expr1 expr2 ...]` — update matching rows, e.g
  `[:update :person {:balance inc} [= :id 1]]`.
- `[:insert-or-replace table row ...]` — on a unique conflict, replace the
  colliding row. Needs the unique constraint materialized; without it the row
  just inserts.
- `[:insert-or-merge table cols-or-* row ...]` — on conflict, merge keys.
- `[:insert-or-update table fn-or-map row ...]` — on conflict, update.
- `[:insert-ignore table row ...]` — on conflict, do nothing.

A map of table to rows is a terser multi-table insert:

```clojure
(def db (rel/transact db
          {:person [{:id 6 :name "Barbara"}]
           :order  [{:id 60 :cust-id 6}]}))
```

Rows inserted this way skip schema validation, since the engine does not know
about the registry. Validate at the boundary if that matters to you, or use
`rc/insert` for the rows that need it.

A note on duplicates, since it surprises people: a relation is a set of rows,
so inserting a row that is already there, byte for byte, does nothing. Two
inserts collide only when the rows differ but agree on a constrained key:

```clojure
(rel/transact db
  [:insert :person {:id 1 :name "same"}]
  [:insert :person {:id 1 :name "same"}])   ;; fine, second is a no-op
(rel/transact db
  [:insert :person {:id 1 :name "Ada"}]
  [:insert :person {:id 1 :name "Bob"}])    ;; throws, :unique-key-violation
```

## Watching for changes

`watch` a query and `track-transact` tells you what a transaction added and
removed from it:

```clojure
(def wdb (rel/watch db [[:from :person]]))
(:changes (rel/track-transact wdb [:insert :person {:id 7 :name "Radia"}]))
;; => {[[:from :person]] {:added [{:id 7, :name "Radia"}], :deleted []}}
```

`unwatch` stops tracking. This is the seam a reactive UI or an agent's
change feed would sit on.
