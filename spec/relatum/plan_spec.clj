(ns relatum.plan-spec
  "Laws for the DDL planner. These quantify over declarations an agent may
  actually write, so a regression in parsing, in derived column flags, or in
  the translation to engine constraints shows up as a failed law rather than as
  a table that quietly accepts junk or silently stops enforcing a key."
  (:require [clojure.string :as str]
            [writ.spec :refer [spec ann law graph refine]]))

(spec relatum.plan)

;; A declaration is what an agent sends before anything has been checked; a
;; table is what the planner derived from one. Both are maps, so they are told
;; apart by what the planner adds: a derived table always knows its keys.
(refine Declaration
  [d (Map Keyword Any)]
  (and (contains? d :columns) (not (contains? d :pk))))

(refine Table
  [t (Map Keyword Any)]
  (and (contains? t :pk) (contains? t :columns)))

(ann ok? [Any -> Bool])
(ann known-attributes [-> (List Keyword)])
(ann read-attributes [Any Any (List Any) -> Any])
(ann parse-column [Any Any -> Any])
(ann build-table [Declaration -> Any])
(ann column [Any Any -> Any])
(ann column-names [Any -> (List Keyword)])
(ann planned-table [Any -> (Opt Table)])
(ann value-kind [Any -> (Opt String)])
(ann validate-row [Any Any -> Any])
(ann type-pred [Any -> Any])
(ann check-op [Any Any -> Any])
(ann constraints [Any Any -> Any])
(ann describe [Any -> (Opt Any)])
(ann render [Any -> (Opt String)])
;; public so the inserting edge can build the same clause the constraint does
(ann resolve-reference [Any Any Any -> (Map Keyword Any)])

;; --- a verdict is always a verdict ----------------------------------------
;; Every entry point answers with the same shape, so a caller can branch on
;; :ok without knowing which one it called or what it was given.

(law a-verdict-says-ok-or-what-went-wrong
  (forall [k Keyword]
    (let [v (build-table {:name k :columns [[:id :int]]})]
      (and (contains? v :ok)
           (= (ok? v) (true? (:ok v)))
           (if (ok? v)
             (some? (:value v))
             (and (keyword? (:error v)) (string? (:message v))))))))

(law a-rejection-says-which-table-it-was-about
  (forall [s String]
    (let [v (build-table {:name s :columns [[:id :int]]})]
      (and (not (ok? v))
           (= :bad-table-name (:error v))
           (= s (:table v))))))

(law a-declaration-without-a-column-vector-is-rejected
  (forall [s String]
    (let [v (build-table {:name :t :columns s})]
      (and (not (ok? v)) (= :bad-table (:error v))))))

;; --- a built table keeps what was declared -------------------------------

(law a-built-table-has-the-columns-it-declares
  (forall [k Keyword]
    (let [v (build-table {:name k :columns [[:id :int] [:name :text]]})]
      (and (ok? v)
           (= [:id :name] (column-names (:value v)))
           (= [:id :name] (mapv :name (:columns (:value v))))))))

(law a-column-keeps-its-declared-type
  (forall [n Nat]
    (let [v (build-table {:name :t :columns [[:s (str "varchar(" n ")")]]})]
      (and (ok? v)
           (= n (-> v :value :columns first :type :max-length))))))

(law a-primary-key-is-required-and-unique
  (forall [k Keyword]
    (let [v (build-table {:name k :columns [[:id :int :primary :key]]})
          c (-> v :value :columns first)]
      (and (ok? v)
           (true? (:primary? c))
           (true? (:required? c))
           (true? (:unique? c))
           (false? (:nullable? c))
           (= [:id] (:pk (:value v)))))))

(law a-column-is-unique-exactly-when-something-says-so
  (forall [k Keyword]
    (let [c (-> (build-table {:name k :columns [[:a :int] [:b :int :unique]]})
                :value :columns second)]
      (and (not (:primary? c)) (true? (:unique? c))))))

(law a-plain-column-is-required-unless-declared-nullable
  (forall [k Keyword]
    (let [plain (-> (build-table {:name k :columns [[:a :int]]})
                    :value :columns first)
          nullable (-> (build-table {:name k :columns [[:a :int :nullable]]})
                       :value :columns first)]
      (and (true? (:required? plain))
           (false? (:nullable? plain))
           (true? (:nullable? nullable))
           (false? (:required? nullable))))))

;; --- declarations that contradict themselves are rejected ----------------

(law a-repeated-column-is-rejected
  (forall [k Keyword]
    (let [v (build-table {:name k :columns [[:a :int] [:a :text]]})]
      (and (not (ok? v)) (= :duplicate-column (:error v))))))

(law a-nullable-primary-key-is-rejected
  (forall [k Keyword]
    (let [v (build-table {:name k :columns [[:a :int :primary :key :nullable]]})]
      (and (not (ok? v)) (= :nullable-primary-key (:error v))))))

(law an-unknown-attribute-is-rejected
  (forall [k Keyword]
    (let [v (build-table {:name k :columns [[:a :int :nonsense]]})]
      (and (not (ok? v)) (= :unknown-attribute (:error v))))))

(law an-unknown-type-is-rejected
  (forall [n Nat]
    (let [v (parse-column :t [:a (str "notatype" n)])]
      (and (not (ok? v)) (= :unknown-type (:error v))))))

(law a-short-column-declaration-is-rejected
  (forall [k Keyword]
    (let [v (parse-column :t [k])]
      (and (not (ok? v)) (= :bad-column (:error v))))))

;; --- rows ----------------------------------------------------------------
;; A row is judged against the table it is declared for: a key the table does
;; not have is a rejection, not an extra field the engine quietly keeps.

(law a-row-with-an-undeclared-column-is-rejected
  (forall [n Nat]
    (let [t (:value (build-table {:name :t :columns [[:a :int]]}))
          v (validate-row t {:a 1 (keyword (str "extra" n)) 2})]
      (and (not (ok? v)) (= :unknown-column (:error v))))))

(law a-valid-row-comes-back-with-exactly-the-declared-columns
  (forall [n Int]
    (let [t (:value (build-table {:name :t
                                  :columns [[:a :int] [:b :text :nullable]]}))
          v (validate-row t {:a n})]
      (and (ok? v)
           (= #{:a :b} (set (keys (:value v))))
           (= n (:a (:value v)))
           (nil? (:b (:value v)))))))

(law a-default-fills-an-absent-column
  (forall [_ Unit]
    (let [t (:value (build-table {:name :t
                                  :columns [[:a :int] [:b :int :default 7]]}))]
      (= 7 (:b (:value (validate-row t {:a 1})))))))

(law a-declared-value-beats-the-default
  (forall [n Int]
    (let [t (:value (build-table {:name :t :columns [[:a :int :default 7]]}))]
      (= n (:a (:value (validate-row t {:a n})))))))

(law a-required-column-cannot-be-left-out
  (forall [_ Unit]
    (let [t (:value (build-table {:name :t :columns [[:a :text]]}))
          v (validate-row t {})]
      (and (not (ok? v)) (= :required (:error v))))))

(law a-nullable-column-may-hold-nil
  (forall [_ Unit]
    (let [t (:value (build-table {:name :t :columns [[:a :text :nullable]]}))]
      (ok? (validate-row t {:a nil})))))

(law a-value-of-the-wrong-type-is-rejected
  (forall [s String]
    (let [t (:value (build-table {:name :t :columns [[:a :int]]}))
          v (validate-row t {:a s})]
      (and (not (ok? v)) (= :bad-type (:error v))))))

;; --- constraints ---------------------------------------------------------
;; The declared shape has to reach the engine, otherwise the table is only
;; documentation: every required column needs a :req, every key a :unique, and
;; every typed column a :check.

(law every-required-column-appears-in-the-required-op
  (forall [_ Unit]
    (let [t (:value (build-table {:name :t
                                  :columns [[:a :int] [:b :text :nullable]]}))
          ops (:value (constraints t {}))]
      ;; :a is required, :b is not, and :req leads the ops
      (= [:req :a] (first ops)))))

(law a-single-primary-key-gets-a-unique-constraint
  (forall [k Keyword]
    (let [t (:value (build-table {:name k :columns [[:id :int :primary :key]]}))]
      (boolean (some #{[:unique :id]} (:value (constraints t {})))))))

(law a-typed-column-gets-a-check
  (forall [k Keyword]
    (let [t (:value (build-table {:name k :columns [[:a :int]]}))]
      (boolean (some #(= :check (first %)) (:value (constraints t {})))))))

(law a-nullable-columns-check-admits-nil
  (forall [k Keyword]
    (let [c (-> (build-table {:name k :columns [[:a :int :nullable]]})
                :value :columns first)]
      (= :or (first (:pred (second (check-op :t c))))))))

(law a-reference-to-an-unknown-table-is-rejected
  (forall [k Keyword]
    (let [t (:value (build-table {:name :child
                                  :columns [[:a :int :references k]]}))
          v (constraints t {})]
      ;; the catalog is empty, so however k is spelled the reference is unknown
      (and (not (ok? v)) (= :unknown-references (:error v))))))

;; The planning flow: a declaration on the way in, a verdict out of the
;; checks, a table when the verdict held, and DDL text back out of the table.
(graph planning
  {:start [:declaration {:name :t :columns [[:id :int :primary :key]]}]
   :states {:declaration Declaration
            :verdict Any
            :table (Opt Table)
            :ddl (Opt String)}
   :final [:ddl]
   :edges {:declaration {[build-table] #{:verdict}
                         [resolve-reference Any Any] #{:verdict}}
           :verdict {[planned-table] #{:table}}
           :table {[render] #{:ddl}}}})

(law a-planned-table-is-the-value-of-a-good-verdict
  (forall [k Keyword]
    (let [v (build-table {:name k :columns [[:id :int]]})]
      (= (:value v) (planned-table v)))))

(law a-rendered-table-names-itself-and-its-columns
  (forall [k Keyword]
    (let [t (:value (build-table {:name k
                                  :columns [[:id :int :primary :key] [:name :text]]}))
          text (render t)]
      (and (string? text)
           (str/includes? text (str k))
           (str/includes? text ":id")
           (str/includes? text ":name")))))

(law a-described-table-reports-one-entry-per-column
  (forall [k Keyword]
    (let [t (:value (build-table {:name k
                                  :columns [[:id :int :primary :key] [:name :text]]}))
          d (describe t)]
      (and (= k (:table d))
           (= [:id] (:primary-key d))
           (= 2 (count (:columns d)))
           (= ["int" "text"] (mapv :type (:columns d)))))))

(law a-table-without-a-name-has-no-description
  (forall [_ Unit]
    (and (nil? (describe nil)) (nil? (render nil)))))

;; --- the vocabulary itself -----------------------------------------------
;; These say what the introspection helpers must answer, not merely that they
;; answer something, so an impostor cannot stand in for them.

(law known-attributes-lists-every-attribute-in-order
  (forall [_ Unit]
    (let [as (known-attributes)]
      (and (= 14 (count as))
           (= [:auto :check :default :key :not-null :null :nullable :on
               :on-delete :primary :primary-key :references :required :unique]
              as)
           (= as (vec (sort as)))))))

(law a-column-is-found-by-its-name-and-only-its-name
  (forall [k Keyword]
    (let [t (:value (build-table {:name k
                                 :columns [[:a :int] [:b :text :nullable]]}))]
      (and (= :a (:name (column t :a)))
           (= :b (:name (column t :b)))
           (= 1 (count (filter (fn [c] (= :a (:name c))) (:columns t))))
           (nil? (column t :missing))))))

(law a-type-pred-is-the-engine-predicate-for-the-kind
  (forall [_ Unit]
    (let [t (:value (build-table {:name :t
                                  :columns [[:i :int]
                                            [:s "varchar(3)"]
                                            [:n :text :nullable]]}))
          s (column t :s)]
      (and (= [integer? :i] (type-pred (column t :i)))
           (= [:and [string? :s] [<= [count :s] 3]] (type-pred s))
           (= [string? :n] (type-pred (column t :n)))
           (nil? (type-pred {:name :x :type {:kind :nothing-known}}))))))

(law value-kind-names-the-shape-of-a-value
  (forall [n Int s String k Keyword]
    (and (= "int" (value-kind n))
         (= "text" (value-kind s))
         (= "keyword" (value-kind k))
         (= "bool" (value-kind true))
         (= "real" (value-kind 1.5))
         (nil? (value-kind nil))
         (= "other" (value-kind [n])))))

;; --- the branches an agent can actually reach -----------------------------
;; Each of these is a shape a real declaration uses; the laws above would not
;; notice if the branch stopped working.

(law every-spelling-of-a-key-sets-the-primary-flag
  (forall [k Keyword]
    (and (:primary? (:value (read-attributes k :a [:key])))
         (:primary? (:value (read-attributes k :a [:primary])))
         (:primary? (:value (read-attributes k :a [:primary-key])))
         ;; :primary :key is one attribute pair, not two flags
         (= {:primary? true} (:value (read-attributes k :a [:primary :key])))
         ;; a following attribute is still read after a bare flag
         (= {:primary? true :nullable? true}
            (:value (read-attributes k :a [:key :nullable])))
         (not (contains? (:value (read-attributes k :a [:key])) :key)))))

(law every-spelling-of-not-null-is-required
  (forall [k Keyword]
    (and (:required? (:value (read-attributes k :a [:required])))
         (:required? (:value (read-attributes k :a [:not-null])))
         (:nullable? (:value (read-attributes k :a [:nullable])))
         (:nullable? (:value (read-attributes k :a [:null])))
         (:auto? (:value (read-attributes k :a [:auto]))))))

(law a-value-taking-attribute-consumes-its-value
  (forall [k Keyword v Int]
    (and (= {:on-delete :cascade}
            (:value (read-attributes k :a [:on-delete :cascade])))
         ;; two value-taking attributes in a row are both read
         (= {:on :delete :on-delete :cascade}
            (:value (read-attributes k :a [:on :delete :on-delete :cascade])))
         (= {:default v} (:value (read-attributes k :a [:default v])))
         (= {:references :parent :on-delete :cascade}
            (:value (read-attributes k :a [:references :parent :on-delete :cascade]))))))

(law a-declaration-must-start-with-a-keyword-column-name
  (forall [s String]
    (let [v (parse-column :t [s :int])]
      (and (not (ok? v)) (= :bad-column-name (:error v))))))

(law a-check-attribute-keeps-its-form
  (forall [_ Unit]
    (let [t (:value (build-table {:name :t
                                  :columns [[:a :int :check [:> :a 0]]]}))
          c (column t :a)]
      (and (= [:> :a 0] (:check c))
           (= [:> :a 0] (:check (first (:columns (describe t)))))))))

(law a-too-long-string-is-rejected-as-too-long
  (forall [s String]
    (if (> (count s) 3)
      (let [t (:value (build-table {:name :t :columns [[:a "varchar(3)"]]}))
            v (validate-row t {:a s})]
        (and (not (ok? v))
             (= :too-long (:error v))
             (= 3 (:max-length v))
             (= (count s) (:length v))))
      true)))

(law the-first-bad-column-is-the-one-reported
  (forall [_ Unit]
    (let [t (:value (build-table {:name :t
                                  :columns [[:a :int] [:b :int]]}))
          v (validate-row t {:a "no" :b "no"})]
      (= :a (:column v)))))

(law a-rejection-carries-no-table
  (forall [s String]
    (nil? (planned-table (build-table {:name s :columns [[:id :int]]})))))

(law every-kind-with-a-predicate-gets-one
  (forall [_ Unit]
    (let [t (:value (build-table {:name :t
                                  :columns [[:u :uuid] [:k :keyword]
                                            [:b :bool] [:r :real]]}))]
      (and (= [uuid? :u] (type-pred (column t :u)))
           (= [keyword? :k] (type-pred (column t :k)))
           (= [boolean? :b] (type-pred (column t :b)))
           (= [number? :r] (type-pred (column t :r)))))))

(law a-kind-with-no-predicate-gets-no-check
  (forall [_ Unit]
    (and (nil? (check-op :t {:name :x :type {:kind :nothing-known}}))
         (nil? (type-pred {:name :x :type {:kind :nothing-known}})))))

(law a-composite-key-is-unique-as-a-tuple
  (forall [k Keyword]
    (let [t (:value (build-table {:name k
                                  :columns [[:a :int :primary :key]
                                            [:b :int :primary :key]]}))
          ops (:value (constraints t {}))]
      (and (= [:a :b] (:pk t))
           ;; one tuple constraint, not two column constraints
           (= 1 (count (filter (fn [op] (= :unique (first op))) ops)))
           (boolean (some #{[:unique :a :b]} ops))))))

(law a-table-of-nullable-columns-asks-for-nothing
  (forall [k Keyword]
    (let [t (:value (build-table {:name k
                                  :columns [[:a :int :nullable]
                                            [:b :int :nullable]]}))
          ops (:value (constraints t {}))]
      (not-any? (fn [op] (= :req (first op))) ops))))
