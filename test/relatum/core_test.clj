(ns relatum.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.core :as core]
            [relatum.schema :as s]))

(defn- ex-data-in [t]
  (loop [e t]
    (when e
      (if (some #(contains? (ex-data e) %) [:relatum/error :relatum.engine/error])
        (ex-data e)
        (recur (ex-cause e))))))

(s/create-table :icustomer
                [[:id :int :primary :key]
                 [:name :text]])

(s/create-table :iorder
                [[:id :int :primary :key]
                 [:cust-id :int :nullable :references :icustomer :on-delete :cascade]])

(deftest a-row-that-names-a-real-parent-goes-in
  (let [db (-> (core/create-db)
               (core/insert :icustomer {:id 1 :name "ada"})
               (core/insert :iorder {:id 10 :cust-id 1}))]
    (is (= [{:id 10 :cust-id 1}] (core/rows db :iorder)))))

(deftest a-row-that-names-a-missing-parent-is-rejected-before-anything-is-written
  (let [db (core/insert (core/create-db) :icustomer {:id 1 :name "ada"})]
    (try
      (core/insert db :iorder {:id 10 :cust-id 99})
      (is false "expected the insert to be rejected")
      (catch Throwable t
        (let [d (ex-data-in t)]
          (is (= :foreign-key-violation (:relatum/error d)))
          (is (= :iorder (:table d)))
          (is (= :icustomer (:references d)))
          (is (= 99 (:cust-id (:row d)))))))
    (is (= [{:id 1 :name "ada"}] (core/rows db :icustomer)))
    (is (empty? (core/rows db :iorder)))))

(deftest an-insert-into-a-table-whose-reference-cannot-resolve-is-rejected
  (let [db (core/create-db)]
    (s/create-table* :ibroken [[:id :int :primary :key]
                               [:pid :int :references :nosuchtable]])
    (try
      (core/insert db :ibroken {:id 1 :pid 1})
      (is false "expected the insert to be rejected")
      (catch Throwable t
        (is (= :unknown-references (:relatum/error (ex-data-in t)))))
      (finally
        (s/forget :ibroken)))))

(deftest a-row-whose-reference-is-empty-is-rejected-too
  (let [db (core/create-db)]
    (try
      (core/insert db :iorder {:id 10})
      (is false "expected the insert to be rejected")
      (catch Throwable t
        (is (= :foreign-key-violation (:relatum/error (ex-data-in t))))))
    (is (not= [] (keys db)) "rejecting a row must not add to the graph")
    (is (empty? (core/rows db :iorder)))))
