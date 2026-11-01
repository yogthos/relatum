(ns relatum.query-test
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.query :as query]))

(deftest rows-answers-a-fresh-database-with-nothing
  (is (empty? (query/rows {} [[:from :qt]])))
  (is (empty? (query/rows {} [[:from :qt] [:where [= :id 1]]]))))

(deftest an-inserted-row-comes-back-with-exactly-the-columns-it-was-given
  (let [db (query/insert {} [:insert :qt {:id 1 :name "ada"}])]
    (is (= #{{:id 1 :name "ada"}} (set (query/rows db [[:from :qt]]))))))

(deftest inserting-is-additive
  (let [db (-> {}
               (query/insert [:insert :qt {:id 1 :name "ada"}])
               (query/insert [:insert :qt {:id 2 :name "grace"}]))]
    (is (= #{{:id 1 :name "ada"} {:id 2 :name "grace"}}
           (set (query/rows db [[:from :qt]]))))))

(deftest the-same-row-inserted-twice-appears-once
  (let [row {:id 1 :name "ada"}
        db (-> {} (query/insert [:insert :qt row]) (query/insert [:insert :qt row]))]
    (is (= [row] (query/rows db [[:from :qt]])))))

(deftest a-where-clause-narrows-the-rows
  (let [db (-> {}
               (query/insert [:insert :qt {:id 1 :name "ada"}])
               (query/insert [:insert :qt {:id 2 :name "grace"}]))]
    (is (= [{:id 2 :name "grace"}]
           (query/rows db [[:from :qt] [:where [= :id 2]]])))
    (is (= #{{:id 1 :name "ada"} {:id 2 :name "grace"}}
           (set (query/rows db [[:from :qt] [:where [> :id 0]]]))))))

(deftest a-deleted-row-is-gone
  (let [db (-> {}
               (query/insert [:insert :qt {:id 1 :name "ada"}])
               (query/insert [:insert :qt {:id 2 :name "grace"}]))
        by-clause (query/insert db [:delete :qt [= :id 1]])
        by-exact (query/insert db [:delete-exact :qt {:id 1 :name "ada"}])]
    (is (= #{{:id 2 :name "grace"}} (set (query/rows by-clause [[:from :qt]]))))
    (is (= #{{:id 2 :name "grace"}} (set (query/rows by-exact [[:from :qt]]))))))
