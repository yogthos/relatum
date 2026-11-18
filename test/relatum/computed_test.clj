(ns relatum.computed-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [relatum.core :as rc]
            [relatum.schema :as s]))

(defn- err [thunk]
  (try (thunk) nil
       (catch Throwable e
         (loop [e e]
           (cond (nil? e) nil
                 (:relatum/error (ex-data e)) (ex-data e)
                 :else (recur (ex-cause e)))))))

(s/create-table :cline
  [[:id    :int  :primary :key]
   [:qty   :int]
   [:price :real]
   [:total :real :as [* :qty :price]]
   [:big?  :bool :as [> :total 100]]])

(s/create-table :cuser
  [[:id    :int  :primary :key]
   [:email :text]
   [:email-key :text :unique :as [str/lower-case :email]]])

(deftest a-computed-column-is-stored-on-insert
  (let [db (rc/insert (rc/create-db) :cline {:id 1 :qty 3 :price 50.0})]
    (is (= {:id 1 :qty 3 :price 50.0 :total 150.0 :big? true}
           (rc/row db :cline 1)))))

(deftest computed-columns-see-earlier-computed-columns
  (let [db (rc/insert (rc/create-db) :cline {:id 1 :qty 1 :price 5.0})]
    (is (false? (:big? (rc/row db :cline 1))))))

(deftest writing-a-computed-column-is-rejected
  (let [d (err #(rc/insert (rc/create-db) :cline {:id 1 :qty 1 :price 5.0 :total 9.0}))]
    (is (= :generated-column (:relatum/error d)))
    (is (= :total (:column d)))))

(deftest a-computed-value-is-type-checked
  (s/create-table* :cbad [[:id :int :primary :key] [:n :int :as [str :id]]])
  (try
    (let [d (err #(rc/insert (rc/create-db) :cbad {:id 1}))]
      (is (= :bad-type (:relatum/error d)))
      (is (= :n (:column d))))
    (finally (s/forget :cbad))))

(deftest an-expression-that-throws-names-the-column
  (s/create-table* :cthrow [[:id :int :primary :key] [:n :int :nullable]
                            [:m :int :as [inc :n]]])
  (try
    (let [d (err #(rc/insert (rc/create-db) :cthrow {:id 1}))]
      (is (= :generated-failed (:relatum/error d)))
      (is (= :m (:column d))))
    (finally (s/forget :cthrow))))

(deftest a-computed-column-carries-constraints
  (let [db (rc/insert (rc/create-db) :cuser {:id 1 :email "Ada@x.org"})]
    (is (= "ada@x.org" (:email-key (rc/row db :cuser 1))))
    (is (= :unique-key-violation
           (:relatum/error (err #(rc/insert db :cuser {:id 2 :email "ADA@x.org"})))))
    (testing "and its derived index"
      (is (= 1 (:id (get (rc/index db :cuser/by-email-key) "ada@x.org")))))))

(deftest update-recomputes
  (let [db (-> (rc/create-db)
               (rc/insert :cline {:id 1 :qty 1 :price 5.0})
               (rc/update :cline [= :id 1] assoc :qty 30))]
    (is (= 150.0 (:total (rc/row db :cline 1))))
    (is (true? (:big? (rc/row db :cline 1))))))

(deftest update-that-writes-a-computed-column-is-rejected
  (let [db (rc/insert (rc/create-db) :cline {:id 1 :qty 1 :price 5.0})]
    (is (= :generated-column
           (:relatum/error (err #(rc/update db :cline [= :id 1] assoc :total 1.0)))))))

(deftest insert-many-computes-every-row
  (let [db (rc/insert-many (rc/create-db) :cline [{:id 1 :qty 2 :price 1.0}
                                                  {:id 2 :qty 3 :price 1.0}])]
    (is (= #{2.0 3.0} (set (map :total (rc/rows db :cline)))))))

(deftest declarations-are-checked
  (testing "an expression naming an unknown column"
    (let [d (err #(s/build-table* :x [[:a :int] [:b :int :as [inc :nope]]]))]
      (is (= :unknown-column (:relatum/error d)))
      (is (= [:nope] (:columns d)))))
  (testing "an expression naming itself or a later computed column"
    (is (= :generated-order
           (:relatum/error (err #(s/build-table* :x [[:a :int :as [inc :a]]])))))
    (is (= :generated-order
           (:relatum/error (err #(s/build-table* :x [[:a :int] [:b :int :as [inc :c]]
                                                       [:c :int :as [inc :a]]]))))))
  (testing "a plain column named later is fine"
    (is (some? (s/build-table* :x [[:b :int :as [inc :a]] [:a :int]]))))
  (testing ":as does not combine with :default or :auto"
    (is (= :generated-conflict
           (:relatum/error (err #(s/build-table* :x [[:a :int] [:b :int :default 1 :as [inc :a]]])))))
    (is (= :generated-conflict
           (:relatum/error (err #(s/build-table* :x [[:a :int :primary :key :auto :as [inc 1]]]))))))
  (testing ":as needs a value"
    (is (= :missing-attribute-value
           (:relatum/error (err #(s/build-table* :x [[:a :int :as]])))))))

(deftest constants-and-escapes-are-not-columns
  (is (some? (s/build-table* :x [[:a :keyword] [:b :bool :as [= :a [:_ :zzz]]]]))))

(deftest describe-and-ddl-show-the-expression
  (is (= [:* :qty :price]
         (let [g (:generated (some #(when (= :total (:name %)) %) (:columns (s/describe :cline))))]
           (mapv #(if (keyword? %) % :*) g))))
  (is (re-find #":total \"real\" :required :as \[" (s/ddl :cline))))
