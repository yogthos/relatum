(ns relatum.btree-test
  "Queries that the planner answers through a :btree index must find the same
   rows as a scan."
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.core :as rc]
            [relatum.schema :as s]))

(s/create-table :qbt [[:id :int :primary :key] [:g :int] [:pos :int]])
(s/create-table :qbt-plain [[:id :int :primary :key] [:g :int] [:pos :int]])
(s/create-index :qbt [:g :pos] :type :btree)
(s/create-index :qbt [:pos] :type :btree)

(deftest range-predicates-over-btree-indexes
  (testing "every range operator finds the same rows with or without a btree index"
    (let [rows (for [i (range 12)] {:id i :g (mod i 2) :pos (quot i 2)})
          db (reduce (fn [d r] (-> d (rc/insert :qbt r) (rc/insert :qbt-plain r)))
                     (rc/create-db) rows)
          ids (fn [t where] (set (map :id (rc/q db [[:from t] (into [:where] where)]))))]
      (doseq [where [[[> :pos 2]] [[>= :pos 2]] [[< :pos 3]] [[<= :pos 3]]
                     [[:and [= :g 1] [>= :pos 2]]] [[= :g 0] [> :pos 4]]
                     [[>= :pos 0]] [[> :pos 99]]]]
        (is (= (ids :qbt-plain where) (ids :qbt where)) (pr-str where))))))

(deftest update-through-a-range-over-a-btree-index
  (testing "rc/update finds rows by a range on a btree-indexed column"
    (let [db (-> (rc/create-db)
                 (rc/insert :qbt {:id 1 :g 1 :pos 0})
                 (rc/insert :qbt {:id 2 :g 1 :pos 1}))
          db (rc/update db :qbt [>= :pos 1] update :pos inc)]
      (is (= #{0 2} (set (map :pos (rc/rows db :qbt))))))))
