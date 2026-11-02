(ns relatum.regression-test
  "Regressions found in review, pinned here. These are engine behaviors the
  ported relic suite does not cover: incremental group aggregates after
  deletes, query options in map form, single-expression sorts, and a
  jolt-only set-literal throw in the lookup planner."
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.engine :as rel]
            [relatum.schema :as s]))

(deftest a-materialized-count-distinct-survives-a-partial-delete
  (let [q [[:from :A] [:agg [] [:n [rel/count-distinct :x]]]]
        db (rel/mat (rel/transact {} [:insert :A {:id 1 :x "v"} {:id 2 :x "v"}]) q)]
    (is (= [{:n 1}] (rel/q db q)))
    (testing "deleting one of two rows sharing a value keeps the value"
      (is (= [{:n 1}]
             (rel/q (rel/transact db [:delete-exact :A {:id 1 :x "v"}]) q))))
    (testing "deleting the last row holding a value drops it"
      (is (= [{:n 0}]
             (rel/q (rel/transact db [:delete-exact :A {:id 1 :x "v"}]
                                            [:delete-exact :A {:id 2 :x "v"}]) q))))))

(deftest a-materialized-count-distinct-keeps-distinct-values-apart
  (let [q [[:from :A] [:agg [] [:n [rel/count-distinct :x]]]]
        db (rel/mat (rel/transact {} [:insert :A {:id 1 :x "a"} {:id 2 :x "b"}]) q)]
    (is (= [{:n 2}] (rel/q db q)))
    (is (= [{:n 1}] (rel/q (rel/transact db [:delete-exact :A {:id 1 :x "a"}]) q)))))

(deftest a-grouped-count-distinct-survives-a-partial-delete
  (let [q [[:from :A] [:agg [:g] [:n [rel/count-distinct :x]]]]
        db (rel/mat (rel/transact {} [:insert :A {:id 1 :g 1 :x "v"}]
                                        [:insert :A {:id 2 :g 1 :x "v"}]) q)]
    (is (= [{:g 1 :n 1}] (rel/q db q)))
    (is (= [{:g 1 :n 1}]
           (rel/q (rel/transact db [:delete-exact :A {:id 1 :g 1 :x "v"}]) q)))))

(deftest a-materialized-set-concat-survives-a-partial-delete
  (let [q [[:from :A] [:agg [] [:xs [rel/set-concat :x]]]]
        db (rel/mat (rel/transact {} [:insert :A {:id 1 :x "v"} {:id 2 :x "v"}]) q)]
    (is (= [{:xs #{"v"}}] (rel/q db q)))
    (is (= [{:xs #{"v"}}]
           (rel/q (rel/transact db [:delete-exact :A {:id 1 :x "v"}]) q)))
    (is (= [{:xs #{}}]
           (rel/q (rel/transact db [:delete-exact :A {:id 1 :x "v"}]
                                          [:delete-exact :A {:id 2 :x "v"}]) q)))))

(deftest a-range-lookup-through-a-unique-index-answers
  ;; a jolt-only throw: the planner built a set literal from two identical
  ;; values, which the JVM reader dedupes silently and jolt rejects
  (let [db (-> {} (rel/mat [[:from :A] [:unique :a]])
               (rel/transact [:insert :A {:a 1} {:a 2}]))]
    (is (= [{:a 2}] (rel/q db [[:from :A] [:where [> :a 1]]])))
    (is (= [{:a 1} {:a 2}] (rel/q db [[:from :A] [:where [>= :a 1]]])))))

(deftest query-options-in-map-form-reach-the-query
  (let [db (rel/transact {} [:insert :A {:a 1} {:a 2}])]
    (is (= {::all [1 2]}
           (rel/q db {::all {:q [[:from :A]] :into [] :xf (map :a)}})))
    (is (= {::rows [{:a 1} {:a 2}]}
           (rel/q db {::rows [[:from :A]]})))))

(deftest a-single-expression-sort-accepts-a-bare-keyword
  (let [db (rel/transact {} [:insert :A {:a 2} {:a 1}])]
    (is (= [{:a 1} {:a 2}] (rel/q db [[:from :A]] {:sort :a})))
    (is (= [{:a 2} {:a 1}] (rel/q db [[:from :A]] {:rsort :a})))))

(deftest a-single-expression-sort-accepts-a-bare-function
  (let [db (rel/transact {} [:insert :A {:a 2} {:a 1}])
        by-a (fn [row] (:a row))]
    (is (= [{:a 1} {:a 2}] (rel/q db [[:from :A]] {:sort by-a})))
    (is (= [{:a 2} {:a 1}] (rel/q db [[:from :A]] {:rsort by-a})))))

(deftest get-env-returns-the-environment-itself
  (let [db (rel/with-env {} {:k 1})]
    (is (= {:k 1} (rel/get-env db)))
    (is (= {:k 2} (rel/get-env (rel/update-env db update :k inc))))))

(defn- engine-err [thunk]
  (try (thunk) nil
       (catch Throwable t
         (loop [e t]
           (cond (nil? e) nil
                 (:relatum.engine/error (ex-data e)) (:relatum.engine/error (ex-data e))
                 :else (recur (ex-cause e)))))))

(deftest a-declared-check-is-enforced-by-the-engine
  (s/create-table* :rchk [[:id :int :primary :key] [:qty :int :check [< :qty 100]]])
  (let [db (rel/mat {} (s/constraint-query (s/table :rchk)))]
    (is (nil? (engine-err #(rel/transact db [:insert :rchk {:id 1 :qty 5}]))))
    (is (= :check-violation
           (engine-err #(rel/transact db [:insert :rchk {:id 2 :qty 500}]))))))

(deftest a-false-check-is-enforced-by-the-engine
  (s/create-table* :rfalsechk [[:id :int :primary :key] [:qty :int :check false]])
  (let [db (rel/mat {} (s/constraint-query (s/table :rfalsechk)))]
    (is (= :check-violation
           (engine-err #(rel/transact db [:insert :rfalsechk {:id 1 :qty 1}]))))))

(deftest a-date-column-rejects-a-string-even-through-the-raw-engine
  (s/create-table* :rdate [[:id :int :primary :key] [:d :date]])
  (let [db (rel/mat {} (s/constraint-query (s/table :rdate)))]
    (is (nil? (engine-err #(rel/transact db [:insert :rdate
                                              {:id 1 :d (java.time.LocalDate/now)}]))))
    (is (= :check-violation
           (engine-err #(rel/transact db [:insert :rdate {:id 2 :d "2026-01-01"}]))))))
