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

(s/create-table :iswap
  [[:id :int :primary :key]
   [:v :int :unique]])

(deftest an-update-may-swap-unique-values-atomically
  (let [db (-> (core/create-db)
               (core/insert :iswap {:id 1 :v 1})
               (core/insert :iswap {:id 2 :v 2}))]
    (is (= [{:id 1 :v 2} {:id 2 :v 1}]
           (sort-by :id
                    (core/rows (core/update db :iswap [> :id 0]
                                            (fn [r] (assoc r :v (if (= 1 (:id r)) 2 1))))
                               :iswap))))))

(deftest an-update-that-would-break-the-shape-rejects-atomically
  (let [db (-> (core/create-db)
               (core/insert :iswap {:id 1 :v 1})
               (core/insert :iswap {:id 2 :v 2}))]
    (try
      (core/update db :iswap [> :id 0] (fn [r] (assoc r :v "nope")))
      (is false "expected the update to be rejected")
      (catch Throwable t
        (is (= :bad-type (:relatum/error (ex-data-in t))))))
    (is (= [{:id 1 :v 1} {:id 2 :v 2}] (sort-by :id (core/rows db :iswap)))
        "a rejected update must leave the database untouched")))

(s/create-table :ichk
  [[:id :int :primary :key]
   [:qty :int :check [< :qty 100]]])

(deftest a-declared-check-is-enforced-on-insert
  (let [db (core/create-db)]
    (is (some? (core/insert db :ichk {:id 1 :qty 5})))
    (try
      (core/insert db :ichk {:id 2 :qty 500})
      (is false "expected the insert to be rejected")
      (catch Throwable t
        (is (= :check-violation (:relatum.engine/error (ex-data-in t)))
            "the engine raises it, like its other materialized constraints")))))

(deftest a-false-check-is-enforced-on-insert
  (s/create-table* :ifalsechk [[:id :int :primary :key] [:qty :int :check false]])
  (try
    (core/insert (core/create-db) :ifalsechk {:id 1 :qty 1})
    (is false "expected the insert to be rejected")
    (catch Throwable t
      (is (= :check-violation (:relatum.engine/error (ex-data-in t)))))))
