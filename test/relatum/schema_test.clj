(ns relatum.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.schema :as s]))

;; Compile-time errors on jolt arrive wrapped by the compiler; walk the cause
;; chain for our ex-data so the assertions stay readable.
(defn- err [thunk]
  (try (thunk) nil
       (catch Throwable e
         (loop [e e]
           (cond (nil? e) nil
                 (:relatum/error (ex-data e)) (ex-data e)
                 :else (recur (.getCause e)))))))

(deftest parse-type-test
  (testing "sql-ish strings"
    (is (= {:kind :varchar :max-length 32 :sql "varchar(32)"}
           (dissoc (s/parse-type "varchar(32)") :pred :hint)))
    (is (= {:kind :text :sql "text"}
           (dissoc (s/parse-type "text") :pred :hint)))
    (is (= 8 (:max-length (s/parse-type "VARCHAR( 8 )")))))
  (testing "bare keywords and strings for the scalar types"
    (doseq [[t kind] [[:int :int] ["int" :int] [:integer :int] [:bigint :int]
                      [:real :real] ["real" :real] [:double :real] [:float :real]
                      [:bool :bool] ["boolean" :bool]
                      [:uuid :uuid] [:keyword :keyword]
                      [:date :date] [:timestamp :timestamp]]]
      (is (= kind (:kind (s/parse-type t))) (str t))))
  (testing "only real carries a coercing hint"
    (is (= 'double (:hint (s/parse-type :real))))
    (is (nil? (:hint (s/parse-type :int))))))

(deftest parse-type-unknown-test
  (let [d (err #(s/parse-type "varchr(32)"))]
    (is (= :unknown-type (:relatum/error d)))
    (is (= "varchr(32)" (:declared d)))
    (is (seq (:known d)) "the error should name the types that are understood")))

(deftest create-table-test
  (s/create-table* :fruit
    [[:name "varchar(32)" :primary :key]
     [:appearance "varchar(32)"]
     [:cost :int]
     [:grade :real]])
  (let [t (s/table :fruit)]
    (is (= :fruit (:name t)))
    (is (= [:name :appearance :cost :grade] (map :name (:columns t))))
    (is (= [:name] (:pk t)))
    (is (= :varchar (-> t :columns first :type :kind)))
    (is (= :real (-> t :columns (nth 3) :type :kind)))))

(deftest primary-key-implies-required-and-unique-test
  (s/create-table* :pk-demo [[:id :int :primary :key] [:v :text]])
  (let [id (-> (s/table :pk-demo) :columns first)]
    (is (:required? id))
    (is (:unique? id))
    (is (not (:nullable? id)))))

(deftest contradictory-column-test
  (testing "a primary key cannot be nullable"
    (is (= :nullable-primary-key
           (:relatum/error (err #(s/create-table* :bad1 [[:id :int :primary :key :nullable]])))))))

(deftest duplicate-column-test
  (is (= :duplicate-column
         (:relatum/error (err #(s/create-table* :bad2 [[:id :int] [:id :text]]))))))

(deftest unknown-type-in-table-test
  (is (= :unknown-type
         (:relatum/error (err #(s/create-table* :bad3 [[:id "nonsense"]]))))))

(deftest composite-primary-key-test
  (s/create-table* :line [[:order-id :int :primary :key]
                          [:n :int :primary :key]
                          [:qty :int]])
  (is (= [:order-id :n] (:pk (s/table :line))))
  (is (= [:order-id :n] (map :name (filter :primary? (:columns (s/table :line)))))))

(deftest validate-row-test
  (s/create-table* :fruit2
    [[:name "varchar(4)" :primary :key]
     [:cost :int]
     [:grade :real]
     [:note :text :nullable]])
  (let [t (s/table :fruit2)]
    (testing "a good row passes through, ints coerce to double for :real"
      (is (= {:name "ab" :cost 1 :grade 3.0 :note nil}
             (s/coerce-row t {:name "ab" :cost 1 :grade 3}))))
    (testing "primary key is mandatory"
      (is (= :required (:relatum/error (err #(s/coerce-row t {:cost 1})))))
      (is (= :required (:relatum/error (err #(s/coerce-row t {:name nil :cost 1}))))))
    (testing "varchar length is enforced"
      (let [d (err #(s/coerce-row t {:name "abcde"}))]
        (is (= :too-long (:relatum/error d)))
        (is (= 4 (:max-length d)))
        (is (= 5 (:length d)))))
    (testing "declared type is enforced"
      (is (= :bad-type (:relatum/error (err #(s/coerce-row t {:name "ab" :cost "nope"})))))
      (is (= :bad-type (:relatum/error (err #(s/coerce-row t {:name "ab" :cost 1.5}))))))
    (testing "nullable accepts nil, non-nullable does not"
      (is (= {:name "ab" :cost 1 :grade 0.0 :note nil}
             (s/coerce-row t {:name "ab" :cost 1 :grade 0.0 :note nil})))
      (is (= :required (:relatum/error (err #(s/coerce-row t {:name "ab" :cost nil}))))))))

(deftest default-value-test
  (s/create-table* :fruit-def
    [[:name :text :primary :key]
     [:qty :int :default 1]
     [:note :text :nullable]])
  (let [t (s/table :fruit-def)]
    (is (= 1 (:qty (s/coerce-row t {:name "a"}))) "a default fills an absent column")
    (is (= 5 (:qty (s/coerce-row t {:name "a" :qty 5}))) "a supplied value wins")
    (is (= :required (:relatum/error (err #(s/coerce-row (s/build-table* :no-def [[:n :text :primary :key] [:q :int]]) {:n "a"})))))))

(deftest unknown-column-test
  (s/create-table* :fruit3 [[:name :text :primary :key]])
  (let [d (err #(s/coerce-row (s/table :fruit3) {:name "a" :nope 1}))]
    (is (= :unknown-column (:relatum/error d)))
    (is (= :nope (:column d)))))

(deftest an-attribute-that-needs-a-value-rejects-a-truncated-tail
  (is (= :missing-attribute-value
         (:relatum/error (err #(s/build-table* :bad-tail [[:pid :int :references]])))))
  (is (= :missing-attribute-value
         (:relatum/error (err #(s/build-table* :bad-tail2 [[:x :text :default]])))))
  (is (= :missing-attribute-value
         (:relatum/error (err #(s/build-table* :bad-tail3 [[:x :text :check]])))))
  (testing "an explicit nil value is rejected too: a nil default would mean no
            default at all, and a nil :references/:on/:on-delete/:check is
            never meaningful"
    (is (= :missing-attribute-value
           (:relatum/error (err #(s/build-table* :bad-tail4 [[:x :text :default nil]])))))
    (is (= :missing-attribute-value
           (:relatum/error (err #(s/build-table* :bad-tail5 [[:x :int :references nil]])))))))

(deftest on-attribute-must-name-a-column-of-the-referenced-table
  (s/create-table* :fkparent [[:id :int :primary :key]])
  (try
    (s/create-table* :on-bad [[:pid :int :references :fkparent :on :bogus]])
    (is (= :unknown-on-column
           (:relatum/error (err #(s/ddl->constraints (s/table :on-bad))))))
    (finally (s/forget :on-bad)))
  (try
    (s/create-table* :on-good [[:pid :int :references :fkparent :on :id]])
    (is (contains? (set (s/ddl->constraints (s/table :on-good)))
                   [:fk :fkparent {:pid :id} nil]))
    (finally
      (s/forget :on-good)
      (s/forget :fkparent))))

(deftest ddl->constraints-test
  (s/create-table* :fruit4
    [[:name "varchar(8)" :primary :key]
     [:cost :int :required]
     [:grade :real]
     [:color :text :unique]])
  (let [ops (s/ddl->constraints (s/table :fruit4))]
    (is (some #{[:req :name :cost :grade :color]} ops)
        "non-nullable columns are required")
    (is (some #(= :unique (first %)) ops))
    (is (some #(= :check (first %)) ops) "declared types become :check constraints")))

(deftest conforms-test
  (is (s/conforms? (s/parse-type :int) 3))
  (is (not (s/conforms? (s/parse-type :int) 3.5)))
  (is (not (s/conforms? (s/parse-type :int) "3")))
  (is (s/conforms? (s/parse-type :bool) true))
  (is (s/varchar-conforms? 4 "abcd"))
  (is (not (s/varchar-conforms? 4 "abcde")))
  (testing "conforms? agrees with parse-type's own pred"
    (let [t (s/parse-type "varchar(4)")]
      (is (s/conforms? t "abc"))
      (is (not (s/conforms? t "abcde"))))))

(deftest describe-test
  (let [d (s/describe :pk-demo)]
    (is (= :pk-demo (:table d)))
    (is (= [:id] (:primary-key d)))
    (is (= "int" (-> d :columns first :type)))
    (testing "the description round-trips through pr-str"
      (is (string? (pr-str d))))))

(deftest ddl-render-test
  (let [txt (s/ddl :pk-demo)]
    (is (re-find #"create-table :pk-demo" txt))
    (is (re-find #":id" txt))
    (is (re-find #":primary" txt))))

(s/create-table :mfruit
  [[:name "varchar(32)" :primary :key]
   [:appearance "varchar(32)"]
   [:cost :int]
   [:grade :real]])

(deftest create-table-macro-test
  (testing "the macro registers the schema"
    (is (= [:name :appearance :cost :grade] (s/column-names :mfruit))))
  (testing "and defrecords a type named after the table"
    (is (some? (resolve 'relatum.schema-test/->Mfruit))))
  (testing "a non-nullable :real column coerces on construction"
    (let [r ((resolve 'relatum.schema-test/->Mfruit) "a" "b" 1 2)]
      (is (= 2.0 (:grade r)))
      (is (= java.lang.Double (class (:grade r))))))
  (testing "compile-time rejection of a bad declaration"
    (is (= :unknown-type
           (:relatum/error (err #(eval '(relatum.schema/create-table :badm [[:x "nope"]]))))))
    (is (= :duplicate-column
           (:relatum/error (err #(eval '(relatum.schema/create-table :badm2 [[:x :int] [:x :int]]))))))))
