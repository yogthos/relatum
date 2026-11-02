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
- `:on-delete :cascade` — deleting a parent row deletes its children.
- `:auto` — marks the column as automatically supplied. relatum records the
  flag in the column description and does not generate values itself.

Without `:nullable`, every column is required, so most declarations never say
`:required` explicitly. Say `:nullable` for the ones you want optional.

Attributes that take a value (`:default`, `:check`, `:references`, `:on`,
`:on-delete`) must actually get one. A truncated tail like
`[:pid :int :references]` rejects with `:missing-attribute-value` rather than
silently dropping the reference.

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
distinct names everywhere. `create-db` installs constraints for every
registered table, so declare before you build a database.

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
