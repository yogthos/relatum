(ns relatum.bulk-test
  "Bulk writes through the validating surface (insert-many, insert-tables)
   and column lookups (lookup)."
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.core :as rc]
            [relatum.engine :as rel]
            [relatum.schema :as s]))

(s/create-table :bk-file
                [[:id   :int  :primary :key :auto]
                 [:path :text :required]
                 [:lang :keyword :default :clj]])

(s/create-table :bk-var
                [[:id      :int  :primary :key :auto]
                 [:file-id :int  :references :bk-file :on-delete :cascade]
                 [:name    :text :unique]
                 [:line    :int]])

(s/create-table :bk-node
                [[:id     :int :primary :key]
                 [:parent :int :references :bk-node]])

(s/create-table :bk-tag
                [[:code :keyword :primary :key]
                 [:group :keyword]])

;; a reference to a column that is neither a key nor unique, so no index
;; serves the parent side
(s/create-table :bk-node
                [[:id     :int :primary :key]
                 [:parent :int :references :bk-node]])

(s/create-table :bk-tagged
                [[:id    :int :primary :key]
                 [:group :keyword :references :bk-tag :on :group]])

(s/create-index :bk-var [:file-id :line] :type :btree)

(defn- thrown-data [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e
         (loop [x e]
           (when x
             (if (contains? (ex-data x) :relatum/error) (ex-data x) (recur (ex-cause x))))))))

(defn- one-at-a-time [db table rows]
  (reduce #(rc/insert %1 table %2) db rows))

;; ---------------------------------------------------------------------------
;; insert-many

(deftest insert-many-assigns-the-ids-successive-inserts-would
  (let [db (rc/insert-many (rc/create-db) :bk-file [{:path "a"} {:path "b"}])
        rows [{:path "c"} {:id 10 :path "d"} {:path "e"} {:id 4 :path "f"} {:path "g"}]]
    (is (= #{1 2} (set (map :id (rc/rows db :bk-file)))))
    (is (= (set (rc/rows (one-at-a-time db :bk-file rows) :bk-file))
           (set (rc/rows (rc/insert-many db :bk-file rows) :bk-file))))
    (is (= [3 10 11 4 12]
           (map #(:id (first (rc/lookup (rc/insert-many db :bk-file rows) :bk-file :path %)))
                ["c" "d" "e" "f" "g"])))))

(deftest insert-many-fills-defaults-and-coerces
  (let [db (rc/insert-many (rc/create-db) :bk-file [{:path "a"} {:path "b" :lang :cljs}])]
    (is (= #{{:id 1 :path "a" :lang :clj} {:id 2 :path "b" :lang :cljs}}
           (set (rc/rows db :bk-file))))))

(deftest a-rejected-row-rejects-the-whole-batch
  (let [db (rc/insert (rc/create-db) :bk-file {:path "a"})]
    (testing "a schema rejection names the row's problem"
      (let [d (thrown-data #(rc/insert-many db :bk-file [{:path "b"} {:path 7}]))]
        (is (= :bad-type (:relatum/error d)))
        (is (= :path (:column d)))))
    (testing "a unique collision inside the batch is an engine rejection with the same key"
      (let [d (thrown-data #(rc/insert-many db :bk-var [{:file-id 1 :name "x" :line 1}
                                                        {:file-id 1 :name "x" :line 2}]))]
        (is (= :unique-key-violation (:relatum/error d)))))
    (testing "the db the caller holds is the db before the batch"
      (is (= 1 (count (rc/rows db :bk-file))))
      (is (empty? (rc/rows db :bk-var))))))

(deftest insert-many-checks-every-reference-and-reports-all-offenders
  (let [db (rc/insert (rc/create-db) :bk-file {:path "a"})
        d (thrown-data #(rc/insert-many db :bk-var [{:file-id 1 :name "ok" :line 1}
                                                    {:file-id 8 :name "bad1" :line 1}
                                                    {:file-id 9 :name "bad2" :line 1}]))]
    (is (= :foreign-key-violation (:relatum/error d)))
    (is (= :bk-var (:table d)))
    (is (= :bk-file (:references d)))
    (is (= "bad1" (:name (:row d))))
    (is (= ["bad1" "bad2"] (map :name (:rows d))))))

(deftest rows-of-a-batch-may-reference-each-other
  (testing "a self reference to a row later in the batch"
    (let [db (rc/insert-many (rc/create-db) :bk-node [{:id 2 :parent 1} {:id 1 :parent 1}])]
      (is (= 2 (count (rc/rows db :bk-node))))))
  (testing "a self reference to nothing is still rejected"
    (is (= :foreign-key-violation
           (:relatum/error (thrown-data #(rc/insert-many (rc/create-db) :bk-node
                                                         [{:id 1 :parent 1} {:id 2 :parent 5}])))))))

(deftest an-unindexed-reference-is-checked-against-the-parent-rows
  (let [db (rc/insert-many (rc/create-db) :bk-tag [{:code :a :group :g1} {:code :b :group :g1}])]
    (is (= 2 (count (rc/rows (rc/insert-many db :bk-tagged [{:id 1 :group :g1} {:id 2 :group :g1}])
                             :bk-tagged))))
    (is (= :foreign-key-violation
           (:relatum/error (thrown-data #(rc/insert-many db :bk-tagged [{:id 1 :group :g2}])))))))

(deftest a-db-without-installed-indexes-still-gets-its-references-checked
  ;; a hand-built db: no constraints, no access indexes to read parents from
  (let [db (rel/transact {} {:bk-file [{:id 1 :path "a" :lang :clj}]})]
    (is (= 1 (count (rc/rows (rc/insert db :bk-var {:file-id 1 :name "v" :line 1}) :bk-var))))
    (is (= :foreign-key-violation
           (:relatum/error (thrown-data #(rc/insert db :bk-var {:file-id 2 :name "v" :line 1})))))))

(deftest an-empty-batch-is-the-same-db
  (let [db (rc/create-db)]
    (is (identical? db (rc/insert-many db :bk-file [])))
    (is (identical? db (rc/insert-tables db {})))))

;; ---------------------------------------------------------------------------
;; insert-tables

(deftest insert-tables-loads-related-tables-in-one-go
  (let [loaded {:bk-var [{:id 1 :file-id 2 :name "f" :line 3}
                         {:id 2 :file-id 1 :name "g" :line 1}]
                :bk-file [{:id 1 :path "a.clj"} {:id 2 :path "b.clj" :lang :cljs}]}
        db (rc/insert-tables (rc/create-db) loaded)]
    (testing "children may be listed before their parents"
      (is (= 2 (count (rc/rows db :bk-var))))
      (is (= #{"a.clj" "b.clj"} (set (map :path (rc/rows db :bk-file))))))
    (testing "rows are validated and defaulted"
      (is (= :clj (:lang (rc/row db :bk-file 1)))))
    (testing "the result equals inserting parents then children one by one"
      (is (= (set (rc/rows db :bk-var))
             (set (rc/rows (-> (rc/create-db)
                               (one-at-a-time :bk-file (:bk-file loaded))
                               (one-at-a-time :bk-var (:bk-var loaded)))
                           :bk-var)))))
    (testing "cascades still run over a bulk-loaded db"
      (is (= #{"g"} (set (map :name (rc/rows (rc/delete db :bk-file [= :id 2]) :bk-var))))))))

(deftest insert-tables-takes-pairs-and-numbers-a-repeated-table-once
  (let [db (rc/insert-tables (rc/create-db) [[:bk-file [{:path "a"}]]
                                             [:bk-var [{:file-id 1 :name "v" :line 1}]]
                                             [:bk-file [{:path "b"}]]])]
    (is (= #{[1 "a"] [2 "b"]} (set (map (juxt :id :path) (rc/rows db :bk-file)))))
    (is (= 1 (count (rc/rows db :bk-var))))))

(deftest insert-tables-rejects-as-a-whole
  (let [db (rc/create-db)]
    (is (= :foreign-key-violation
           (:relatum/error (thrown-data #(rc/insert-tables db {:bk-file [{:id 1 :path "a"}]
                                                               :bk-var [{:file-id 2 :name "v" :line 1}]})))))
    (is (= :unknown-table
           (:relatum/error (thrown-data #(rc/insert-tables db {:bk-file [{:path "a"}]
                                                               :bk-nope [{:x 1}]})))))
    (is (empty? (rc/rows db :bk-file)))))

(deftest reference-checks-follow-a-redeclared-table
  ;; the per-table fk resolution is cached; a new declaration must retire it
  (s/create-table* :bk-a [[:id :int :primary :key]])
  (s/create-table* :bk-b [[:id :int :primary :key]])
  (try
    (s/create-table* :bk-c [[:id :int :primary :key] [:p :int :references :bk-a]])
    (let [db (rc/insert-tables (rc/create-db) {:bk-a [{:id 1}] :bk-b [{:id 2}]})]
      (is (= 1 (count (rc/rows (rc/insert db :bk-c {:id 1 :p 1}) :bk-c))))
      (s/create-table* :bk-c [[:id :int :primary :key] [:p :int :references :bk-b]])
      ;; db still carries the old engine constraint (to :bk-a, where 1 is
      ;; present), so only the core check, against :bk-b, can reject this
      (is (= :foreign-key-violation (:relatum/error (thrown-data #(rc/insert db :bk-c {:id 1 :p 1})))))
      (let [db (rc/insert-tables (rc/create-db) {:bk-a [{:id 1}] :bk-b [{:id 2}]})]
        (is (= 1 (count (rc/rows (rc/insert db :bk-c {:id 1 :p 2}) :bk-c))))))
    (finally (s/forget :bk-c) (s/forget :bk-a) (s/forget :bk-b))))

;; ---------------------------------------------------------------------------
;; lookup

(defn- db1 []
  (rc/insert-tables (rc/create-db)
                    {:bk-file [{:id 1 :path "a"} {:id 2 :path "b"}]
                     :bk-var [{:id 1 :file-id 1 :name "f" :line 10}
                              {:id 2 :file-id 1 :name "g" :line 20}
                              {:id 3 :file-id 2 :name "h" :line 10}]}))

(defn- by-q [db table col v]
  (set (rc/q db [[:from table] [:where [= col v]]])))

(deftest lookup-agrees-with-the-equivalent-query
  (let [db (db1)]
    (doseq [[col v] [[:file-id 1] [:file-id 2] [:file-id 99]   ; fk hash index
                     [:id 2] [:id 99]                          ; pk unique index
                     [:name "g"]                               ; unique column
                     [:line 10] [:line 11]                     ; no index
                     [:name nil]]]                             ; nil falls back
      (is (= (by-q db :bk-var col v) (rc/lookup db :bk-var col v)) (pr-str [col v])))))

(deftest lookup-reads-an-index-led-by-the-column
  (let [db (db1)]
    (testing "a hash hit is the index's own set"
      (is (identical? (get (rc/index db :bk-var/by-file-id) 1) (rc/lookup db :bk-var :file-id 1))))
    (testing "a column only a composite leads still reads its index"
      (s/create-table* :bk-pos [[:id :int :primary :key] [:doc :int] [:pos :int]])
      (s/create-index :bk-pos [:doc :pos] :type :btree)
      (try
        (let [db (rc/insert-many (rc/create-db) :bk-pos [{:id 1 :doc 1 :pos 0} {:id 2 :doc 1 :pos 1}
                                                         {:id 3 :doc 2 :pos 0}])]
          (is (= #{1 2} (set (map :id (rc/lookup db :bk-pos :doc 1)))))
          (is (= #{} (rc/lookup db :bk-pos :doc 5))))
        (finally (s/forget :bk-pos))))
    (testing "an index the db does not keep falls back to a query"
      (let [hand (rel/transact {} {:bk-var (rc/rows db :bk-var)})]
        (is (= (by-q db :bk-var :file-id 1) (rc/lookup hand :bk-var :file-id 1)))))))

(deftest lookup-rejects-names-the-schema-does-not-declare
  (is (= :unknown-table (:relatum/error (thrown-data #(rc/lookup (db1) :bk-nope :id 1)))))
  (is (= :unknown-column (:relatum/error (thrown-data #(rc/lookup (db1) :bk-var :nope 1))))))
