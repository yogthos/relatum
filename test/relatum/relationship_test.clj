(ns relatum.relationship-test
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.core :as rc]
            [relatum.engine :as rel]
            [relatum.schema :as s]))

(defn- err [thunk]
  (try (thunk) nil
       (catch Throwable e
         (loop [e e]
           (cond (nil? e) nil
                 (or (:relatum/error (ex-data e))
                     (:relatum.engine/error (ex-data e))) (ex-data e)
                 :else (recur (.getCause e)))))))

(s/create-table :rcustomer
  [[:id :int :primary :key]
   [:name :text]])

(s/create-table :rorder
  [[:id :int :primary :key]
   [:cust-id :int :references :rcustomer]
   [:total :real :nullable]])

(deftest fk-op-shape-test
  (let [ops (s/ddl->constraints (s/table :rorder))
        fk (some #(when (= :fk (first %)) %) ops)]
    (is (= [:fk :rcustomer {:cust-id :id} nil] fk)
        "an unqualified :references resolves to the parent's primary key")))

(deftest fk-enforced-test
  (let [db (-> {} (rc/table-constraints :rcustomer) (rc/table-constraints :rorder))]
    (testing "a row whose parent exists is accepted"
      (is (some? (-> db
                     (rc/insert :rcustomer {:id 1 :name "a"})
                     (rc/insert :rorder {:id 10 :cust-id 1})))))
    (testing "a dangling reference is rejected"
      (is (some? (err #(rc/insert db :rorder {:id 10 :cust-id 99})))))
    (testing "out-of-order inserts within one transaction are fine"
      (is (some? (rel/transact db
                               [:insert :rorder {:id 10 :cust-id 1}]
                               [:insert :rcustomer {:id 1 :name "a"}]))))))

(s/create-table :cacustomer [[:id :int :primary :key]])
(s/create-table :caorder
  [[:id :int :primary :key]
   [:cust-id :int :references :cacustomer :on-delete :cascade]])

(deftest cascade-op-shape-test
  (let [fk (some #(when (= :fk (first %)) %) (s/ddl->constraints (s/table :caorder)))]
    (is (= [:fk :cacustomer {:cust-id :id} {:cascade :delete}] fk)
        ":on-delete :cascade becomes relic's {:cascade :delete}")))

(deftest cascade-delete-test
  (let [db (-> {}
               (rc/table-constraints :cacustomer)
               (rc/table-constraints :caorder)
               (rc/insert :cacustomer {:id 1})
               (rc/insert :caorder {:id 10 :cust-id 1})
               (rc/insert :caorder {:id 11 :cust-id 1}))
        after (rel/transact db [:delete-exact :cacustomer {:id 1}])]
    (is (= 2 (count (rc/rows db :caorder))))
    (is (empty? (rc/rows after :caorder))
        "deleting the parent removes its children")))

(deftest explicit-referenced-column-test
  (s/create-table :xparent [[:id :int :primary :key] [:code :text :unique]])
  (s/create-table :xchild [[:id :int :primary :key]
                           [:pcode :text :references :xparent :on :code]])
  (let [fk (some #(when (= :fk (first %)) %) (s/ddl->constraints (s/table :xchild)))]
    (is (= [:fk :xparent {:pcode :code} nil] fk))))

(deftest unknown-referenced-table-test
  (s/create-table :ychild [[:id :int :primary :key]
                           [:x :int :references :nope]])
  ;; an invalid declaration left registered would break every later create-db
  (try
    (is (= :unknown-references
           (:relatum/error (err #(s/ddl->constraints (s/table :ychild))))))
    (finally (s/forget :ychild))))

(deftest composite-parent-needs-explicit-column-test
  (s/create-table :zparent [[:a :int :primary :key] [:b :int :primary :key]])
  (s/create-table :zchild [[:id :int :primary :key]
                           [:za :int :references :zparent]])
  (try
    (is (= :ambiguous-references
           (:relatum/error (err #(s/ddl->constraints (s/table :zchild))))))
    (finally (s/forget :zchild))))

(deftest fk-type-mismatch-test
  (s/create-table :wparent [[:id :int :primary :key]])
  (s/create-table :wchild [[:id :int :primary :key]
                           [:pid :text :references :wparent]])
  (try
    (is (= :fk-type-mismatch
           (:relatum/error (err #(s/ddl->constraints (s/table :wchild))))))
    (finally (s/forget :wchild))))
