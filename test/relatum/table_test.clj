(ns relatum.table-test
  "End-to-end: a create-table declaration drives the engine, so the declared
  shape is what the database enforces."
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.core :as rc]
            [relatum.engine :as rel]
            [relatum.schema :as s]))

(defmacro throws-data [data-key & body]
  `(try
     (do ~@body nil)
     (catch Throwable e#
       (let [d# (ex-data e#)]
         (or (and (= ~data-key (:relatum.engine/error d#)) d#)
             (and (= ~data-key (:relatum/error d#)) d#)
             {:unexpected (some-> e# .getMessage)})))))

(s/create-table :tfruit
  [[:name "varchar(8)" :primary :key]
   [:appearance "varchar(8)"]
   [:cost :int]
   [:grade :real]])

(defn- constrained-db [table-name]
  (rel/mat {} (s/constraint-query (s/table table-name))))

(deftest constraint-query-shape-test
  (is (= :req (-> (s/ddl->constraints (s/table :tfruit)) first first)))
  (is (some #(= :unique (first %)) (s/ddl->constraints (s/table :tfruit)))
      "the primary key column must be constrained unique")
  (is (some #(= :check (first %)) (s/ddl->constraints (s/table :tfruit)))))

(deftest insert-and-query-test
  (let [db (rc/insert (constrained-db :tfruit)
                      :tfruit {:name "apple" :appearance "round" :cost 1 :grade 2})]
    (is (= [{:name "apple" :appearance "round" :cost 1 :grade 2.0}]
           (rc/rows db :tfruit))
        "declared :real coerces the stored value")))

(deftest insert-validation-test
  (let [db (constrained-db :tfruit)]
    (testing "the validated insert path rejects before touching the db"
      (is (= :too-long
             (:relatum/error (ex-data (try (rc/insert db :tfruit {:name "muchtoolong" :appearance "r" :cost 1 :grade 1})
                                           (catch Throwable e e)))))))
    (testing "an unknown column is named in the error"
      (is (= :nope
             (:column (ex-data (try (rc/insert db :tfruit {:name "a" :appearance "r" :cost 1 :grade 1 :nope 2})
                                    (catch Throwable e e)))))))))

(deftest primary-key-enforced-test
  (let [db (constrained-db :tfruit)]
    (is (some? (throws-data :unique-violation
                 (rel/transact db
                               [:insert :tfruit {:name "apple" :appearance "r" :cost 1 :grade 1}]
                               [:insert :tfruit {:name "apple" :appearance "s" :cost 2 :grade 2}])))
        "two rows with the same primary key must be rejected")))

(deftest required-enforced-test
  (let [db (constrained-db :tfruit)]
    (is (some? (throws-data :check-violation
                 (rel/transact db [:insert :tfruit {:name "apple" :cost 1 :grade 1}])))
        "a missing non-nullable column must be rejected")))

(deftest type-enforced-test
  (let [db (constrained-db :tfruit)]
    (testing "declared :int rejects a string"
      (is (some? (throws-data :check-violation
                   (rel/transact db [:insert :tfruit {:name "apple" :appearance "r" :cost "one" :grade 1}])))))
    (testing "declared varchar(8) rejects an over-long string"
      (is (some? (throws-data :check-violation
                   (rel/transact db [:insert :tfruit {:name "averylongname" :appearance "r" :cost 1 :grade 1}])))))))

(deftest composite-key-test
  (s/create-table :line
    [[:order-id :int :primary :key]
     [:n :int :primary :key]
     [:qty :int]])
  (let [db (constrained-db :line)]
    (testing "the same order-id may repeat with a different n"
      (is (some? (rel/transact db
                              [:insert :line {:order-id 1 :n 1 :qty 2}]
                              [:insert :line {:order-id 1 :n 2 :qty 3}]))))
    (testing "but not the same tuple"
      (is (some? (throws-data :unique-violation
                   (rel/transact db
                                 [:insert :line {:order-id 1 :n 1 :qty 2}]
                                 [:insert :line {:order-id 1 :n 1 :qty 9}])))))))

(deftest nullable-and-default-test
  (s/create-table :gadget
    [[:id :int :primary :key]
     [:label :text :nullable]
     [:qty :int :default 1]])
  (let [db (constrained-db :gadget)]
    (is (some? (rel/transact db [:insert :gadget {:id 1 :qty 5}]))
        "a nullable column may be absent")))
