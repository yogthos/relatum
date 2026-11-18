# CSV

`relatum.csv` moves tables in and out of CSV text.

```clojure
(require '[relatum.schema :as s]
         '[relatum.core :as rc]
         '[relatum.csv :as csv])

(s/create-table :person
  [[:id      :int  :primary :key]
   [:name    :text]
   [:balance :real :default 0.0]
   [:active  :bool :nullable]])
```

## Loading a table

```clojure
(def db (csv/insert (rc/create-db) :person
                    "id,name,balance,active\n1,Ada,100,true\n2,Grace,,\n"))

(rc/row db :person 2)
;; => {:id 2, :name "Grace", :balance 0.0, :active nil}
```

The first line names the columns; pass `{:columns [:id :name]}` when the text
has no header. Each cell is read by its column's declared type (the table in
[types.md](types.md#reading-values-from-text)), then the rows are validated
and inserted like `rc/insert-many` would: defaults filled, `:auto` keys
assigned, computed columns derived, foreign keys checked, all in one
transaction. A bad file leaves the database untouched.

A blank cell is a missing value, so the column's default applies, or nil on a
`:nullable` column. A computed column in the header is ignored, so a table
exported with its computed columns loads back.

`csv/read-rows` does the reading without the insert, returning the typed rows.

## Errors

Every rejection carries `:relatum/error` and the `:line` it came from (the
line a row starts on, when a quoted cell spans several):

| error | when |
|-------|------|
| `:unreadable` | a cell doesn't read as its column's type (`:column`, `:value`, `:expected`) |
| `:unknown-column` | a header names a column the table doesn't have |
| `:csv-width` | a row has more or fewer cells than the header |
| `:csv-unterminated-quote` | a quoted cell never closes |
| anything `rc/insert` raises | `:required`, `:too-long`, `:foreign-key-violation`, ... |

## Exporting

```clojure
(csv/export db :person)
;; => "id,name,balance,active\n1,Ada,100.0,true\n2,Grace,0.0,\n"
```

A table exports every declared column, computed ones included, in
declaration order, rows sorted by primary key. A query exports `:columns`
(by default the keys of its rows, sorted), ordered by `:sort` if given:

```clojure
(csv/export db [[:from :person] [:where [> :balance 50]]]
            {:columns [:id :name] :sort [:name]})
```

nil is written as an empty cell, keywords without their colon, everything else
with `str`: what the reader takes back.

## The format

`csv/parse` and `csv/write` are the format on its own, RFC 4180: a cell
holding the separator, a quote or a line break is quoted, and a quote inside
one is doubled. Lines end in LF or CRLF; blank lines are skipped.

```clojure
(csv/parse "a,\"b,c\",,\"\"")    ;; => [["a" "b,c" nil ""]]
(csv/write [["a" "b,c" nil ""]]) ;; => "a,\"b,c\",,\"\"\n"
```

An unquoted empty cell parses as nil and a quoted one as `""`, so a missing
value and an empty string stay distinct, and `write` keeps them so. Every
function takes `:separator` for files split on something other than a comma.
