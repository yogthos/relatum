(ns relatum.unique-test
  "Uniqueness across several columns: (create-table ... :unique [[:a :b]])."
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.core :as rc]
            [relatum.schema :as s]))

(s/create-table :ut-doc [[:id :int :primary :key]])

(s/create-table :ut-layer
                [[:id  :int :primary :key :auto]
                 [:doc :int :references :ut-doc :on-delete :cascade]
                 [:pos :int]]
                :unique [[:doc :pos]]
                :record false)

;; ordered reads over the same columns are a separate, declared index
(s/create-index :ut-layer [:doc :pos] :type :btree)

(defn- err [f] (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- db0 []
  (-> (rc/create-db)
      (rc/insert :ut-doc {:id 1})
      (rc/insert :ut-doc {:id 2})
      (rc/insert :ut-layer {:doc 1 :pos 0})
      (rc/insert :ut-layer {:doc 1 :pos 1})))

(deftest a-repeated-combination-is-rejected
  (testing "the same (doc, pos) twice"
    (is (= :unique-key-violation (:relatum/error (err #(rc/insert (db0) :ut-layer {:doc 1 :pos 1}))))))
  (testing "each column on its own may repeat"
    (is (= 3 (count (rc/rows (rc/insert (db0) :ut-layer {:doc 2 :pos 1}) :ut-layer))))
    (is (= 3 (count (rc/rows (rc/insert (db0) :ut-layer {:doc 1 :pos 2}) :ut-layer)))))
  (testing "an update that would collide is rejected and leaves the db as it was"
    (let [db (db0)]
      (is (= :unique-key-violation
             (:relatum/error (err #(rc/update db :ut-layer [= :pos 0] assoc :pos 1)))))
      (is (= [0 1] (map :pos (rc/children db :ut-layer/by-doc+pos 1)))))))

(deftest shifting-and-swapping-stay-legal
  (testing "making room by shifting every position up is one valid transaction"
    (let [db (-> (db0)
                 (rc/update :ut-layer [:and [= :doc 1] [>= :pos 1]] update :pos inc)
                 (rc/insert :ut-layer {:doc 1 :pos 1}))]
      (is (= [0 1 2] (map :pos (rc/children db :ut-layer/by-doc+pos 1))))))
  (testing "swapping two positions"
    (let [db (rc/update (db0) :ut-layer [= :doc 1] update :pos #(- 1 %))]
      (is (= #{0 1} (set (map :pos (rc/rows db :ut-layer))))))))

(deftest declarations-are-checked
  (let [cols [[:id :int :primary :key] [:a :int] [:b :int]]
        reason #(:relatum/error (err (fn [] (apply s/create-table* :ut-bad cols %&))))]
    (is (= :unknown-column (reason :unique [[:a :nope]])))
    (is (= :duplicate-column (reason :unique [[:a :a]])))
    (is (= :bad-unique (reason :unique [[]])))
    (is (= :bad-unique (reason :unique [:a :b])) "a list of column lists, not one list")
    (is (= :bad-unique (reason :unique :a)))
    (is (nil? (s/table :ut-bad)) "a rejected declaration registers nothing")
    (testing "an option relatum doesn't know is an error, not silently dropped"
      (is (= :unknown-table-option (reason :uniq [[:a :b]]))))))

(deftest it-shows-up-in-describe-and-ddl
  (is (= [[:doc :pos]] (:unique (s/describe :ut-layer))))
  (is (re-find #":unique \[\[:doc :pos\]\]" (s/ddl :ut-layer))))
