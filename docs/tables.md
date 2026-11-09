# Tables

A table is declared once, and after that every insert is checked against the
declaration. The declaration lives in a global registry, so a table you declare
at startup is visible to every later call.

```clojure
(require '[relatum.schema :as s])
```

## Declaring a table

A column is `[name type & attributes]`, and the columns are a vector of those
vectors. Two ways to declare:

```clojure
;; compile time: validated when the macro expands, so a bad declaration is a
;; build error, not a surprise at runtime. Also defrecords a type named after
;; the table (Person) in the calling namespace.
(s/create-table :person
  [[:id      :int          :primary :key]
   [:name    "varchar(64)" :required]
   [:balance :real         :default 0.0]])

;; runtime: same validation, for schemas loaded from data
(s/create-table* :order
  [[:id      :int :primary :key]
   [:cust-id :int :references :person :on-delete :cascade]
   [:total   :real :nullable]])
```

Pass `:record false` to the macro to skip the generated record type:

```clojure
(s/create-table :tag [[:id :int :primary :key] [:label :text]]
  :record false)
```

The macro and the fn agree on the shape. A non-keyword table name, a column
vector that isn't `[name type ...]`, a non-keyword column name, an unknown
type, or a repeated column name each rejects with its own error keyword, at
expansion time for the macro and via an exception for `create-table*`.

## Column attributes

- `:primary :key` (or `:primary-key`, or `:key` on its own) — marks the
  primary key. A table may have several, which makes a composite key. A
  primary key is required and unique by derivation, and cannot be `:nullable`.
- `:required` / `:not-null` — the column must be present and non-nil.
- `:nullable` / `:null` — the column may be nil or absent.
- `:unique` — no two rows may share a value.
- `:default v` — fills the column when a row leaves it out. An explicit nil
  default is rejected, because it would mean no default at all.
- `:check form` — an engine expression every value must satisfy, for example
  `:check [< :qty 100]`.
- `:references table` — a foreign key. The referenced column is the parent's
  primary key unless you name one with `:on`.
- `:on column` — which column of the referenced table the key points at.
- `:on-delete :cascade` — deleting a parent row deletes its children. No
  other value is accepted; an unknown one rejects with `:unknown-on-delete`.
- `:auto` — on a single integer primary key, `rc/insert` fills the column
  when the row leaves it out: one more than the largest key present (1 for an
  empty table). `rc/next-id` answers which key that is, for when you need the
  id before inserting. A supplied value is kept. On other columns the flag is
  only recorded.

Without `:nullable`, every column is required, so most declarations never say
`:required` explicitly. Say `:nullable` for the ones you want optional. The
two together are contradictory and reject with `:required-and-nullable`.

Attributes that take a value (`:default`, `:check`, `:references`, `:on`,
`:on-delete`) must actually get one. A truncated tail like
`[:pid :int :references]` rejects with `:missing-attribute-value` rather than
silently dropping the reference.

## Uniqueness across columns

`:unique` on a column covers that column alone. A rule over a combination,
like SQL's table-level `UNIQUE (doc_id, position)`, goes after the columns:

```clojure
(s/create-table :layer
  [[:id       :int :primary :key :auto]
   [:doc-id   :int :references :doc :on-delete :cascade]
   [:position :int]]
  :unique [[:doc-id :position]])
```

Each inner vector is one combination that must be unique as a tuple (each
column may still repeat on its own). A write that repeats one rejects with
`:unique-key-violation`; an `rc/update` that shifts or swaps values is one
transaction, so it succeeds whenever its end state is valid. A malformed
`:unique` rejects with `:bad-unique`, an unknown column with
`:unknown-column`, and an option `create-table` doesn't know with
`:unknown-table-option` rather than being ignored.

The rule doesn't derive an access index; declare one over the same columns if
you read through them, a `:btree` for order (see `rc/children`).

## The registry

```clojure
(s/table :person)          ;; the table description, or nil
(s/tables)                 ;; every registered table, keyed by name
(s/column :person :name)   ;; one column's description, or nil
(s/column-names :person)   ;; => [:id :name :balance]
(s/forget :tag)            ;; drops a table from the registry
```

The registry is global per jolt process. If two namespaces declare `:person`
differently, the last declaration wins, which is why the test suite uses
distinct names everywhere. A sibling registry holds declared access indexes
(`s/create-index`, `s/forget-index`, listed by `s/access-indexes`); primary
keys, unique columns, and foreign keys derive theirs without a declaration.
`create-db` installs the constraints and every access index for all
registered tables, so declare tables and indexes first, or install late
arrivals with `rc/table-constraints` / `rc/ensure-index`. `s/forget` drops a
table's declared indexes with it.

## Introspection

Two functions turn a table back into something readable. `describe` gives
plain data, safe to `pr-str` for an agent to read:

```clojure
(s/describe :person)
;; => {:table :person,
;;     :primary-key [:id],
;;     :columns [{:name :id, :primary? true, :required? true, :nullable? false,
;;                :unique? true, :default nil, :check nil, :type "int"}
;;               ...],
;;     :references []}
```

`ddl` renders the table back to DDL text, close to what `create-table`
accepts:

```clojure
(println (s/ddl :person))
;; (create-table :person
;;    [[:id :int :primary :key]
;;     ...
```

## Validation without registering

`build-table*` validates a declaration and returns the table description
without touching the registry. It throws on a bad declaration, so it is also
the cheapest way to check one:

```clojure
(s/build-table* :temp [[:id :int :primary :key]])
```

Rows are validated separately, at insert time, against the table
description. That part is covered in [Writing data](writing-data.md).
