(ns relatum.ergonomics-test
  "The validating surface's conveniences: query options, uniform error keys,
   predicate deletes, update with args, primary-key reads, :auto keys."
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.core :as rc]
            [relatum.schema :as s]))

(s/create-table :erg-person
                [[:id      :int  :primary :key :auto]
                 [:name    :text :unique]
                 [:balance :real :default 0.0 :check [>= :balance 0.0]]])

(s/create-table :erg-pet
                [[:id       :int  :primary :key :auto]
                 [:owner-id :int  :references :erg-person :on-delete :cascade]
                 [:position :int]])

(s/create-index :erg-pet [:owner-id :position] :type :btree)

(s/create-table :erg-pair
                [[:a :int :primary :key]
                 [:b :int :primary :key]
                 [:v :text]])

(defn- db0 []
  (-> (rc/create-db)
      (rc/insert :erg-person {:id 1 :name "ada" :balance 10.0})
      (rc/insert :erg-person {:id 2 :name "bob" :balance 5.0})))

(defn- thrown-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest q-takes-options
  (testing "rc/q passes options (sorting) through to the engine"
    (is (= ["ada" "bob"] (map :name (rc/q (db0) [[:from :erg-person]] {:sort [:name]}))))
    (is (= ["bob" "ada"] (map :name (rc/q (db0) [[:from :erg-person]] {:sort [:balance]}))))))

(deftest engine-rejections-carry-the-relatum-error-key
  (testing "a unique violation reads the same as a schema rejection"
    (let [d (thrown-data #(rc/insert (db0) :erg-person {:id 3 :name "ada"}))]
      (is (= :unique-key-violation (:relatum/error d)))
      (is (= :unique-key-violation (:relatum.engine/error d)) "the engine's key is kept")))
  (testing "a check violation from update"
    (let [d (thrown-data #(rc/update (db0) :erg-person [= :id 1] assoc :balance -1.0))]
      (is (= :check-violation (:relatum/error d)))))
  (testing "schema rejections are unchanged"
    (is (= :bad-type (:relatum/error (thrown-data #(rc/insert (db0) :erg-person {:id "x" :name "z"})))))))

(deftest delete-with-a-where
  (let [db (db0)]
    (testing "deletes only the matching rows"
      (is (= [2] (map :id (rc/rows (rc/delete db :erg-person [= :id 1]) :erg-person)))))
    (testing "several expressions all have to hold"
      (is (= #{1 2} (set (map :id (rc/rows (rc/delete db :erg-person [= :id 1] [> :balance 50.0]) :erg-person))))))
    (testing "cascades to children"
      (let [db (rc/insert db :erg-pet {:id 7 :owner-id 1 :position 0})]
        (is (empty? (rc/rows (rc/delete db :erg-person [= :id 1]) :erg-pet)))))
    (testing "without a where it still empties the table"
      (is (empty? (rc/rows (rc/delete db :erg-person) :erg-person))))))

(deftest update-with-args
  (testing "update applies (f row & args), like clojure.core/update"
    (let [db (rc/update (db0) :erg-person [= :id 1] assoc :balance 99.0)]
      (is (= 99.0 (:balance (rc/row db :erg-person 1))))))
  (testing "a plain function still works"
    (let [db (rc/update (db0) :erg-person [= :id 2] #(update % :balance + 1.0))]
      (is (= 6.0 (:balance (rc/row db :erg-person 2)))))))

(deftest row-by-primary-key
  (let [db (rc/insert (db0) :erg-pair {:a 1 :b 2 :v "x"})]
    (is (= "ada" (:name (rc/row db :erg-person 1))))
    (is (nil? (rc/row db :erg-person 99)))
    (testing "a composite key is a vector in declared order"
      (is (= "x" (:v (rc/row db :erg-pair [1 2]))))
      (is (nil? (rc/row db :erg-pair [2 1]))))
    (testing "a table without a key is an error, not nil"
      (is (= :unknown-table (:relatum/error (thrown-data #(rc/row db :no-such-table 1))))))))

(deftest auto-keys
  (testing "an absent :auto key gets the next integer"
    (let [db (rc/insert (db0) :erg-person {:name "cy"})]
      (is (= "cy" (:name (rc/row db :erg-person 3))))))
  (testing "next-id is what the next insert would assign"
    (is (= 3 (rc/next-id (db0) :erg-person)))
    (is (= 1 (rc/next-id (rc/create-db) :erg-person))))
  (testing "a supplied key is kept"
    (is (= "dee" (:name (rc/row (rc/insert (db0) :erg-person {:id 40 :name "dee"}) :erg-person 40)))))
  (testing "insert-many numbers each row"
    (let [db (rc/insert-many (db0) :erg-person [{:name "e"} {:name "f"}])]
      (is (= ["e" "f"] (map #(:name (rc/row db :erg-person %)) [3 4])))))
  (testing "next-id needs a single integer :auto key"
    (is (= :no-auto-key (:relatum/error (thrown-data #(rc/next-id (db0) :erg-pair)))))))

(deftest ordered-children-through-a-btree-index
  (testing "a [parent position] btree index reads a parent's children in order"
    (let [db (-> (db0)
                 (rc/insert :erg-pet {:owner-id 1 :position 2})
                 (rc/insert :erg-pet {:owner-id 1 :position 0})
                 (rc/insert :erg-pet {:owner-id 1 :position 1}))]
      (is (= [0 1 2] (map :position (rc/children db :erg-pet/by-owner-id+position 1)))))))
