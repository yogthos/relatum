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

`insert-many` validates every row like `insert`, then writes them all in one
transaction: a rejected row rejects the batch, and rows may reference each
other in either order. `:auto` keys left out are numbered as successive
inserts would number them, from one counter rather than a rescan per row.

To load several tables at once, `rc/insert-tables` takes a map of table to
rows (or a sequence of `[table rows]` pairs). It validates and defaults every
row, checks every foreign key against the database plus the rows being
loaded, and writes the lot as a single transaction, parents first whatever
order the tables are given in:

```clojure
(def db (rc/insert-tables db {:order  [{:id 70 :cust-id 7}]
                              :person [{:id 7 :name "Edsger"}]}))
```

This is the bulk-load path: validation costs a pass over the rows, not a
transaction per row, so there is little left to gain by going around it.

## Updating

`rc/update` takes a where-expression and a function of the old row, plus any
extra arguments for it, like `clojure.core/update`. It is read, then delete
and re-insert, so the new row shape is validated against the schema too:

```clojure
(def db (rc/update db :person [= :id 1] assoc :balance 10.0))
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
(rc/delete db :person [= :id 1])            ;; the rows matching the expression
(rc/delete db :person [< :balance 0] [= :name "x"])   ;; every expression must hold
(rc/delete db :person)                      ;; every row of the table
```

With foreign keys declared `:on-delete :cascade`, deleting a parent's rows
takes the children with it.

## Keys

An `:auto` integer primary key is filled in when a row leaves it out, and
`rc/row` reads one row by its key (a vector for a composite key), from the
key's index:

```clojure
(rc/next-id db :person)                     ;; the key the next insert assigns
(rc/row db :person 1)                       ;; the row, or nil
```

## Errors

Every rejection from `rc/insert`, `rc/update` and `rc/delete` carries
`:relatum/error` in its ex-data: schema rejections (`:bad-type`,
`:required`, `:foreign-key-violation`, ...) and engine ones
(`:unique-key-violation`, `:check-violation`) alike. Engine rejections keep
their `:relatum.engine/error` key and the engine's exception as the cause.

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
about the registry: no defaults, no `:auto` keys, no type checks.
`rc/insert-tables` takes the same map and does all of that in one
transaction too.

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
