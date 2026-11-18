(ns relatum.csv
  "CSV in and out of relatum tables.

  The format is RFC 4180: fields separated by commas (or :separator), a field
  holding the separator, a quote or a line break is quoted, and a quote inside
  one is doubled. Lines end in LF or CRLF.

  Text has no types, so a cell is read by its column's declared type
  (relatum.type/read-value): \"42\" goes into an :int column as 42, \"true\"
  into a :bool as true. An unquoted empty cell is a missing value, so a
  column's default applies; a quoted empty cell \"\" is the empty string.
  Rows are validated and inserted like any other, in one transaction, and
  computed columns are derived rather than read - a table exported with them
  loads back.

    (require '[relatum.csv :as csv])

    (-> (rc/create-db)
        (csv/insert :person \"id,name,balance\\n1,Ada,100\\n\")
        (csv/export :person))
    ;; => \"id,name,balance\\n1,Ada,100.0\\n\""
  (:require [clojure.string :as str]
            [relatum.core :as rc]
            [relatum.schema :as s]))

;; ---------------------------------------------------------------------------
;; the format

(defn- unterminated [line]
  (ex-info (str "Unterminated quoted field starting on line " line)
           {:relatum/error :csv-unterminated-quote :line line}))

(defn- parse-lines
  "Parse text into [line fields] pairs, line being the 1-based line the row
  starts on. A blank line is skipped."
  [text sep]
  (let [n (count text)
        sb (StringBuilder.)]
    (loop [i 0
           state :start          ; :start :unquoted :quoted :after-quote
           quoted? false         ; was the field being read quoted
           row (transient [])
           rows (transient [])
           line 1                ; the line i is on
           row-line 1            ; the line the current row started on
           quote-line 1]         ; the line the open quote started on
      (let [field (fn []
                    (let [v (if (and (not quoted?) (zero? (.length sb))) nil (str sb))]
                      (.setLength sb 0)
                      v))
            end-row (fn [row]
                      (let [r (persistent! row)]
                        (if (= [nil] r) rows (conj! rows [row-line r]))))]
        (if (>= i n)
          (cond
            (= state :quoted) (throw (unterminated quote-line))
            (and (= state :start) (zero? (count row))) (persistent! rows)
            :else (persistent! (end-row (conj! row (field)))))
          (let [c (.charAt ^String text i)]
            (case state
              :quoted
              (cond
                (= c \") (recur (inc i) :after-quote true row rows line row-line quote-line)
                :else (do (.append sb c)
                          (recur (inc i) :quoted true row rows
                                 (if (= c \newline) (inc line) line) row-line quote-line)))

              ;; :start :unquoted :after-quote
              (cond
                (and (= c \") (= state :after-quote))
                (do (.append sb c)
                    (recur (inc i) :quoted true row rows line row-line quote-line))

                (and (= c \") (= state :start))
                (recur (inc i) :quoted true row rows line row-line line)

                (= c sep)
                (let [v (field)]
                  (recur (inc i) :start false (conj! row v) rows line row-line quote-line))

                (or (= c \newline) (= c \return))
                (let [crlf? (and (= c \return) (< (inc i) n) (= \newline (.charAt ^String text (inc i))))
                      next-i (if crlf? (+ i 2) (inc i))
                      rows (if (and (= state :start) (zero? (count row)))
                             rows
                             (end-row (conj! row (field))))]
                  (recur next-i :start false (transient []) rows
                         (inc line) (inc line) quote-line))

                :else
                (do (.append sb c)
                    (recur (inc i) :unquoted quoted? row rows line row-line quote-line))))))))))

(defn parse
  "Parse CSV text into a vector of rows, each a vector of strings. An unquoted
  empty field is nil and a quoted one \"\" is the empty string, so the two
  stay distinguishable. Blank lines are skipped. An unterminated quote throws
  {:relatum/error :csv-unterminated-quote :line n}.

  opts: :separator, a char (default \\,)."
  ([text] (parse text nil))
  ([text {:keys [separator] :or {separator \,}}]
   (mapv second (parse-lines text separator))))

(defn- cell-text
  "A value as the text read-value takes back: keywords without their colon,
  everything else as str."
  [v]
  (if (keyword? v) (subs (str v) 1) (str v)))

(defn- quote-cell [sep v]
  (if (nil? v)
    ""
    (let [t (cell-text v)]
      (if (or (= "" t)
              (str/includes? t "\"")
              (str/includes? t (str sep))
              (str/includes? t "\n")
              (str/includes? t "\r"))
        (str "\"" (str/replace t "\"" "\"\"") "\"")
        t))))

(defn write
  "Write rows (sequences of values) as CSV text, every line ending in a
  newline. nil is an empty field; the empty string is written quoted, \"\", so
  parse reads each back as it was. Keywords lose their colon, other values are
  written with str.

  opts: :separator, a char (default \\,)."
  ([rows] (write rows nil))
  ([rows {:keys [separator] :or {separator \,}}]
   (let [sb (StringBuilder.)
         sep (str separator)]
     (doseq [row rows]
       (.append sb (str/join sep (map #(quote-cell separator %) row)))
       (.append sb "\n"))
     (str sb))))

;; ---------------------------------------------------------------------------
;; tables

(defn- table! [table-name]
  (or (s/table table-name)
      (throw (ex-info (str "Unknown table " table-name)
                      {:relatum/error :unknown-table :table table-name
                       :known (vec (keys (s/tables)))}))))

(defn- at-line
  "Run f, adding the CSV line to the ex-data of a relatum rejection."
  [line f]
  (try (f)
       (catch clojure.lang.ExceptionInfo e
         (if (contains? (ex-data e) :relatum/error)
           (throw (ex-info (ex-message e) (assoc (ex-data e) :line line) e))
           (throw e)))))

(defn- read-lines
  "[line typed-row] pairs for CSV text, each row read by its table's types."
  [table-name text {:keys [columns separator] :or {separator \,}}]
  (table! table-name)
  (let [lines (parse-lines text separator)
        [header body] (if columns
                        [(vec columns) lines]
                        [(mapv #(keyword (or % "")) (second (first lines))) (rest lines)])]
    (mapv (fn [[line fields]]
            (when (not= (count fields) (count header))
              (throw (ex-info (str "Line " line " has " (count fields) " fields, the header has "
                                   (count header))
                              {:relatum/error :csv-width :table table-name :line line
                               :expected (count header) :actual (count fields)})))
            [line (at-line line #(s/read-row table-name (zipmap header fields)))])
          body)))

(defn read-rows
  "The typed rows CSV text holds for `table-name`, without inserting them,
  each read by relatum.schema/read-row. The first line is the header unless
  :columns names the columns. A rejection carries the :line it came from;
  a row with the wrong number of fields is :csv-width.

  opts: :columns, :separator."
  ([table-name text] (read-rows table-name text nil))
  ([table-name text opts] (mapv second (read-lines table-name text opts))))

(defn insert
  "Insert the rows of CSV text into `table-name`, in one transaction: a bad
  row leaves the database untouched. Each cell is read by its column's type,
  a blank cell takes the column's default, and computed columns are derived,
  not read. The first line is the header unless :columns names the columns.
  A row that does not read or validate is reported with its :line.

    (csv/insert db :person \"id,name\\n1,Ada\\n\")
    (csv/insert db :person \"1,Ada\\n\" {:columns [:id :name]})

  opts: :columns, :separator."
  ([db table-name text] (insert db table-name text nil))
  ([db table-name text opts]
   (let [t (table! table-name)
         lines (read-lines table-name text opts)]
     ;; validate each row here, not only in insert-many, so a rejection can
     ;; name the line it came from; insert-many's own check is then a formality
     (doseq [[line r] lines] (at-line line #(s/coerce-row t r)))
     (rc/insert-many db table-name (mapv second lines)))))

(defn- column-label [k]
  (if (keyword? k) (subs (str k) 1) (str k)))

(defn export
  "CSV text for a table or a query, header first.

  A table name exports every declared column, computed ones included, in
  declaration order, rows sorted by primary key. A query exports :columns
  (default: the keys of its rows, sorted), in the order :sort gives, if any.

    (csv/export db :person)
    (csv/export db [[:from :person] [:where [> :balance 0]]] {:columns [:id :name]})

  opts: :columns, :sort, :separator."
  ([db table-or-query] (export db table-or-query nil))
  ([db table-or-query {:keys [columns sort] :as opts}]
   (let [[query cols sort]
         (if (keyword? table-or-query)
           (let [t (table! table-or-query)]
             [[[:from table-or-query]]
              (or columns (s/column-names table-or-query))
              (or sort (not-empty (:pk t)))])
           [table-or-query columns sort])
         rows (if sort (rc/q db query {:sort sort}) (rc/q db query))
         cols (or cols (vec (clojure.core/sort (into #{} (mapcat keys) rows))))]
     (write (cons (map column-label cols)
                  (map (fn [r] (map #(get r %) cols)) rows))
            (select-keys opts [:separator])))))
