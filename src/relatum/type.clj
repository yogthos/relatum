(ns relatum.type
  "The type vocabulary: what a declared column type means, and the single
  predicate that decides whether a value conforms to it.

  Deliberately pure and throw-free so its laws can be proved (writ does not
  check effect code). Reporting a bad declaration is relatum.schema's job.")

(defn varchar-length
  "The n in \"varchar(n)\", or nil."
  [s]
  (when-let [m (re-matches #"(?i)\s*(?:var)?char\s*\(\s*(\d+)\s*\)\s*" (str s))]
    (parse-long (second m))))

(def ^:private scalar-types
  {"text"     {:kind :text :sql "text" :hint nil :pred string?}
   "string"   {:kind :text :sql "text" :hint nil :pred string?}
   "varchar"  {:kind :text :sql "text" :hint nil :pred string?}
   "char"     {:kind :text :sql "text" :hint nil :pred string?}

   "int"      {:kind :int :sql "int" :hint nil :pred integer?}
   "integer"  {:kind :int :sql "int" :hint nil :pred integer?}
   "bigint"   {:kind :int :sql "int" :hint nil :pred integer?}
   "smallint" {:kind :int :sql "int" :hint nil :pred integer?}
   "long"     {:kind :int :sql "int" :hint nil :pred integer?}

   "real"     {:kind :real :sql "real" :hint 'double :pred number?}
   "double"   {:kind :real :sql "real" :hint 'double :pred number?}
   "float"    {:kind :real :sql "real" :hint 'double :pred number?}
   "decimal"  {:kind :real :sql "real" :hint 'double :pred number?}
   "numeric"  {:kind :real :sql "real" :hint 'double :pred number?}

   "bool"     {:kind :bool :sql "bool" :hint nil :pred boolean?}
   "boolean"  {:kind :bool :sql "bool" :hint nil :pred boolean?}

   "uuid"     {:kind :uuid :sql "uuid" :hint nil :pred #(instance? java.util.UUID %)}
   "keyword"  {:kind :keyword :sql "keyword" :hint nil :pred keyword?}
   "kw"       {:kind :keyword :sql "keyword" :hint nil :pred keyword?}
   "date"     {:kind :date :sql "date" :hint nil :pred #(instance? java.time.LocalDate %)}
   "timestamp" {:kind :timestamp :sql "timestamp" :hint nil
                :pred #(instance? java.time.Instant %)}
   "datetime" {:kind :timestamp :sql "timestamp" :hint nil
               :pred #(instance? java.time.Instant %)}
   "instant"  {:kind :timestamp :sql "timestamp" :hint nil
               :pred #(instance? java.time.Instant %)}})

(defn parse-type
  "Parse a declared type into {:kind :sql :hint :pred}, or nil if it is not a
  type this library knows. Accepts strings and keywords."
  [declared]
  (let [s (if (keyword? declared) (name declared) (str declared))
        trimmed (clojure.string/trim s)
        lower (clojure.string/lower-case trimmed)]
    (if-let [n (varchar-length trimmed)]
      {:kind :varchar :max-length n :sql (str "varchar(" n ")")
       :hint nil :pred (fn [v] (and (string? v) (<= (count v) n)))}
      (get scalar-types lower))))

(defn conforms?
  "Does value conform to a parsed type descriptor? The one place a value is
  judged against a declared type."
  [type value]
  (boolean (and type ((:pred type) value))))

(defn varchar-conforms?
  "Does value fit a varchar of the given maximum length?"
  [max-length value]
  (and (string? value) (<= (count value) max-length)))

(defn validate
  "Judge a value against a declared type, returning a verdict rather than
  throwing: {:ok true :value v} when it conforms, {:ok false :error :bad-type}
  or {:ok false :error :too-long} when it does not.

  Callers decide what a rejection means. relatum.schema turns one into
  ex-data; the laws here prove the verdict itself is total and consistent."
  [type value]
  (cond
    (nil? type) {:ok true :value value}
    (not (conforms? type value))
    ;; A varchar only overruns its bound when the value is a string; anything
    ;; else is a plain type mismatch, not a length complaint.
    (if (and (= :varchar (:kind type)) (string? value))
      {:ok false :error :too-long :max-length (:max-length type)}
      {:ok false :error :bad-type :expected (:sql type)})
    (= :real (:kind type)) {:ok true :value (double value)}
    :else {:ok true :value value}))

(defn known-types
  "The type names parse-type understands, sorted so error messages list them
  the same way every time."
  []
  ["bool" "date" "int" "keyword" "real" "text" "timestamp" "uuid" "varchar"])
