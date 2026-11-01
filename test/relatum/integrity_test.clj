(ns relatum.integrity-test
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.integrity :as integrity]))

(def ^:private clause {:cust-id :id})

(deftest a-row-with-a-parent-satisfies-the-reference
  (is (integrity/has-parent? clause {:cust-id 1} [{:id 1 :name "ada"}]))
  (is (not (integrity/has-parent? clause {:cust-id 2} [{:id 1 :name "ada"}]))))

(deftest a-null-reference-is-not-a-wildcard
  (is (not (integrity/has-parent? clause {:cust-id nil} [{:id 1}])))
  (is (not (integrity/has-parent? clause {} [{:id 1}])))
  (is (not (integrity/has-parent? clause {:cust-id nil} []))))

(deftest a-dangling-reference-is-rejected-and-names-the-row
  (let [v (integrity/fk-verdict clause {:cust-id 9} [{:id 1}])]
    (is (= false (:ok v)))
    (is (= :foreign-key-violation (:error v)))
    (is (= {:cust-id 9} (:row v)))
    (is (= clause (:clause v)))))

(deftest a-satisfied-reference-passes-through-unchanged
  (is (= {:ok true :value {:cust-id 1}}
         (integrity/fk-verdict clause {:cust-id 1} [{:id 1 :name "ada"}]))))

(deftest check-finds-only-the-unsatisfied-rows-in-order
  (let [rows [{:cust-id 1} {:cust-id 7} {:cust-id nil} {:cust-id 8}]
        parents [{:id 1}]]
    (is (= [{:cust-id 7} {:cust-id nil} {:cust-id 8}]
           (integrity/check clause rows parents)))
    (is (= [{:cust-id 7} {:cust-id nil} {:cust-id 8}]
           (:rows (integrity/check-verdict clause rows parents))))
    (is (= true (:ok (integrity/check-verdict clause [{:cust-id 1}] parents))))))

(deftest both-directions-of-the-clause-are-read
  (is (integrity/same-key? {:a :b} {:a 1} {:b 1}))
  (is (not (integrity/same-key? {:a :b} {:a 1} {:b 2})))
  (is (integrity/same-key? clause {:cust-id 1} {:id 1}))
  (is (not (integrity/same-key? clause {:cust-id 1} {:id 2}))))
