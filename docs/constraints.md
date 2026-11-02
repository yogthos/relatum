# Constraints

A table declaration is not documentation, it is something the database
enforces. This page says exactly what each part of a declaration turns into,
and what the errors look like when one is broken.

```clojure
(require '[relatum.schema :as s]
         '[relatum.core :as rc])
```

## What a declaration becomes

`s/ddl->constraints` translates a table into the engine's constraint
operations. `create-db` materializes them, and from then on the engine
enforces the shape on every transaction, not just validated inserts:

```clojure
(s/create-table* :fruit
  [[:name  "varchar(8)" :primary :key]
   [:cost  :int]
   [:grade :real]
   [:color :text :unique]])

(s/ddl->constraints (s/table :fruit))
;; => [[:req :name :cost :grade :color]
;;     [:unique :color]
;;     [:unique :name]
;;     [:check {:pred [:and [string? :name] [<= [count :name] 8]],
;;              :error "column :name of :fruit must be varchar(8)"}]
;;     ...]
```

The mapping, attribute by attribute:

- Non-nullable columns become `[:req ...]`.
- `:primary :key` becomes `[:unique pk]`, a single op for a simple key and a
  tuple for a composite one.
- `:unique` becomes `[:unique col]`.
- `:references` becomes a `[:fk parent clause opts]` op.
- Every typed column becomes a `[:check {:pred ... :error ...}]` op, with the
  predicate the type vocabulary dictates.

## Foreign keys

A reference is checked at two moments. `rc/insert` pre-checks it as a pure
decision over the parent rows, so a rejected insert leaves the database
untouched and reports in the schema layer's error shape. The engine's `:fk`
constraint then enforces the same thing continuously, and catches anything
that arrives by another path.

The simplest form points at the parent's primary key:

```clojure
(s/create-table* :cust [[:id :int :primary :key] [:name :text]])
(s/create-table* :ord
  [[:id :int :primary :key]
   [:cust-id :int :references :cust]])
```

Name the referenced column with `:on` when the parent's key isn't what you
mean, for instance a natural key:

```clojure
(s/create-table* :code-child
  [[:id :int :primary :key]
   [:code :text :references :cust :on :name]])
```

What a reference requires:

- The referenced table must be registered, or the declaration rejects with
  `:unknown-references`.
- The `:on` column must exist in the parent, or `:unknown-on-column`.
- The child column's type kind must match the parent column's, or
  `:fk-type-mismatch`.
- A parent with a composite primary key and no `:on` rejects with
  `:ambiguous-references`.
- A nil child value does not satisfy a reference. If the reference is
  optional, declare the column `:nullable` too, and remember an absent
  nullable column still fails the check when the engine runs it, so supply
  parents for every row that carries the column.

A rejected insert reports like this:

```clojure
(rc/insert db :ord {:id 10 :cust-id 99})
;; ExceptionInfo Foreign key violation: :ord references :cust
;; ex-data => {:relatum/error :foreign-key-violation
;;             :table :ord :references :cust
;;             :clause {:cust-id :id}
;;             :row {:id 10, :cust-id 99}}
```

Because both sides of a transaction are checked at the end, inserting a child
and its parent in one `rel/transact` works in either order.

## Cascade deletes

`:on-delete :cascade` makes the engine delete children when their parent
goes:

```clojure
(s/create-table* :ca-order
  [[:id :int :primary :key]
   [:cust-id :int :references :cust :on-delete :cascade]])

(def db (-> {} (rc/table-constraints :cust) (rc/table-constraints :ca-order)))
(def db (-> db
            (rc/insert :cust {:id 1 :name "a"})
            (rc/insert :ca-order {:id 10 :cust-id 1})))

(rc/rows db :ca-order)                      ;; one row
(rc/delete db :cust)                        ;; parent and children gone
```

The cascade only fires on deletes the engine performs, which is every delete
path, since `rc/delete` goes through the engine.

## Error keywords

The schema layer puts `:relatum/error` in ex-data. The engine uses
`:relatum.engine/error`. A caller walking an exception's cause chain sees
whichever layer threw.

From the schema layer:

- `:unknown-table`, `:unknown-column`, `:unknown-type`,
  `:unknown-attribute`, `:missing-attribute-value`, `:unknown-on-column`
- `:bad-table-name`, `:bad-table`, `:bad-column`, `:bad-column-name`
- `:duplicate-column`, `:nullable-primary-key`
- `:required`, `:bad-type`, `:too-long`
- `:unknown-references`, `:ambiguous-references`, `:fk-type-mismatch`
- `:foreign-key-violation`

From the engine, when a transaction breaks a materialized constraint:

- `:check-violation` — a `:check` predicate failed, including type checks.
- `:unique-key-violation` — a `:unique` constraint failed.
- `:foreign-key-violation` — an `:fk` constraint failed.

## Beyond DDL

The constraint ops are not internal. `rel/mat` materializes any query, and a
query that throws is a constraint, so you can enforce things DDL can't
express:

```clojure
(require '[relatum.engine :as rel])

(def db (rel/mat db [[:from :ord] [:check {:pred [< :total 10000]
                                           :error "order too large"}]]))
```

Combined with `[:constrain ...]` to group several, this is the full
mechanism the DDL layer compiles into. The engine's docstrings
(`rel/mat`, `rel/transact`) have the complete op list.
