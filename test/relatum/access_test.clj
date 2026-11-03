(ns relatum.access-test
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.core :as core]
            [relatum.plan :as plan]
            [relatum.schema :as s]))

(s/create-table :acustomer
                [[:id :int :primary :key]
                 [:email "varchar(64)" :unique]
                 [:name :text]])

(s/create-table :aorder
                [[:id :int :primary :key]
                 [:cust-id :int :references :acustomer]
                 [:status :keyword]])

;; ---------------------------------------------------------------------------
;; pure: name derivation

(deftest index-name-is-derived-from-table-and-columns
  (is (= :aorder/by-cust-id (plan/index-name :aorder [:cust-id])))
  (is (= :aorder/by-cust-id+status (plan/index-name :aorder [:cust-id :status])))
  (is (= :acustomer/by-id (plan/index-name :acustomer [:id]))))

;; ---------------------------------------------------------------------------
;; pure: derived indexes

(deftest a-primary-key-derives-a-unique-index
  (let [idx (plan/derived-indexes (s/table :acustomer))]
    (is (contains? (set (map :name idx)) :acustomer/by-id))
    (is (= {:name :acustomer/by-id :table :acustomer :cols [:id]
            :type :unique :origin :pk}
           (some #(when (= :acustomer/by-id (:name %)) %) idx)))))

(deftest a-foreign-key-derives-a-hash-index
  (let [idx (plan/derived-indexes (s/table :aorder))]
    (is (= {:name :aorder/by-cust-id :table :aorder :cols [:cust-id]
            :type :hash :origin :fk}
           (some #(when (= :aorder/by-cust-id (:name %)) %) idx)))))

(deftest a-composite-primary-key-derives-a-unique-index-over-the-tuple
  (let [t (s/build-table* :aline
                          [[:order-id :int :primary :key]
                           [:seq :int :primary :key]
                           [:sku :keyword]])
        idx (plan/derived-indexes t)]
    (is (= {:name :aline/by-order-id+seq :table :aline
            :cols [:order-id :seq] :type :unique :origin :pk}
           (some #(when (= :aline/by-order-id+seq (:name %)) %) idx)))))

(deftest a-table-without-pk-or-fks-derives-no-indexes
  (is (empty? (plan/derived-indexes (s/build-table* :aloc [[:city :text]])))))

;; ---------------------------------------------------------------------------
;; pure: declared composite indexes

(deftest a-declared-index-over-real-columns-is-accepted
  (let [v (plan/build-index (s/table :aorder) [:cust-id :status] {})]
    (is (plan/ok? v))
    (is (= {:name :aorder/by-cust-id+status :table :aorder
            :cols [:cust-id :status] :type :hash :origin :declared}
           (:value v)))))

(deftest a-declared-index-can-be-a-btree
  (let [v (plan/build-index (s/table :aorder) [:status] {:type :btree})]
    (is (plan/ok? v))
    (is (= :btree (-> v :value :type)))))

(deftest a-declared-index-over-an-unknown-column-is-rejected
  (let [v (plan/build-index (s/table :aorder) [:nope] {})]
    (is (not (plan/ok? v)))
    (is (= :unknown-column (:error v)))
    (is (= [:id :cust-id :status] (-> v :known)))))

(deftest a-declared-index-over-repeated-columns-is-rejected
  (is (= :duplicate-column (:error (plan/build-index (s/table :aorder) [:status :status] {})))))

(deftest a-declared-index-without-columns-is-rejected
  (is (= :bad-index (:error (plan/build-index (s/table :aorder) [] {})))))

(deftest a-declared-index-whose-name-collides-with-a-derived-one-is-rejected
  ;; [:cust-id] is the fk's derived name, [:id] the pk's
  (is (= :index-name-conflict (:error (plan/build-index (s/table :aorder) [:cust-id] {}))))
  (is (= :index-name-conflict (:error (plan/build-index (s/table :aorder) [:id] {})))))

(deftest an-index-with-an-unsupported-type-is-rejected
  (is (= :unknown-index-type (:error (plan/build-index (s/table :aorder) [:status] {:type :gin}))))
  (is (plan/ok? (plan/build-index (s/table :aorder) [:status] {:type :hash})))
  (is (plan/ok? (plan/build-index (s/table :aorder) [:status] {:type :btree}))))

;; ---------------------------------------------------------------------------
;; pure: relvar query

(deftest an-index-descriptor-compiles-to-its-relvar-query
  (is (= [[:from :aorder] [:hash :cust-id]]
         (plan/access-query {:table :aorder :cols [:cust-id] :type :hash})))
  (is (= [[:from :aorder] [:btree :cust-id :status]]
         (plan/access-query {:table :aorder :cols [:cust-id :status] :type :btree})))
  (is (= [[:from :acustomer] [:unique :id]]
         (plan/access-query {:table :acustomer :cols [:id] :type :unique}))))

;; ---------------------------------------------------------------------------
;; edge: registry

(deftest create-index-registers-a-declared-index
  (s/create-index :aorder [:cust-id :status])
  (let [idx (s/access-indexes)]
    (is (= {:name :aorder/by-cust-id+status :table :aorder
            :cols [:cust-id :status] :type :hash :origin :declared}
           (get idx :aorder/by-cust-id+status))))
  (s/forget-index :aorder/by-cust-id+status))

(deftest access-indexes-includes-derived-and-declared
  (s/create-index :aorder [:cust-id :status])
  (let [names (set (keys (s/access-indexes)))]
    (is (contains? names :acustomer/by-id))
    (is (contains? names :acustomer/by-email))
    (is (contains? names :aorder/by-id))
    (is (contains? names :aorder/by-cust-id))
    (is (contains? names :aorder/by-cust-id+status)))
  (s/forget-index :aorder/by-cust-id+status))

(deftest create-index-rejects-a-conflicting-redeclaration
  (s/create-index :aorder [:cust-id :status])
  (try
    ;; same columns, different type: same derived name, different index
    (s/create-index :aorder [:cust-id :status] :type :btree)
    (is false "expected the conflicting declaration to be rejected")
    (catch Throwable t
      (is (= :index-name-conflict (:relatum/error (ex-data t)))))
    (finally (s/forget-index :aorder/by-cust-id+status))))

(deftest an-identical-redeclaration-is-idempotent
  (s/create-index :aorder [:cust-id :status])
  (is (= :aorder/by-cust-id+status (:name (s/create-index :aorder [:cust-id :status]))))
  (s/forget-index :aorder/by-cust-id+status))

(deftest forgetting-a-table-drops-its-declared-indexes
  (s/create-table* :atemp [[:id :int :primary :key] [:a :int]])
  (s/create-index :atemp [:a])
  (is (contains? (set (keys (s/access-indexes))) :atemp/by-a))
  (s/forget :atemp)
  (is (not (contains? (set (keys (s/access-indexes))) :atemp/by-a)))
  (is (not (contains? (set (keys (s/access-indexes))) :atemp/by-id))))

;; ---------------------------------------------------------------------------
;; edge: reads

(defn- db-with-data []
  (-> (core/create-db)
      (core/insert-many :acustomer [{:id 1 :name "ada" :email "a@x"}
                                    {:id 2 :name "bob" :email "b@x"}
                                    {:id 3 :name "eve" :email "e@x"}])
      (core/insert-many :aorder [{:id 10 :cust-id 1 :status :shipped}
                                 {:id 11 :cust-id 1 :status :open}
                                 {:id 12 :cust-id 2 :status :shipped}])))

(deftest a-pk-index-reads-as-id-to-row
  (let [db (db-with-data)]
    (is (= {:id 2 :name "bob" :email "b@x"} (get-in (core/index db :acustomer/by-id) [2])))
    (is (= 3 (count (core/index db :acustomer/by-id))))))

(deftest a-unique-column-derives-a-unique-index-too
  (let [db (db-with-data)]
    (is (= {:id 3 :name "eve" :email "e@x"}
           (get-in (core/index db :acustomer/by-email) ["e@x"])))))

(deftest an-fk-index-reads-as-fk-to-rows
  (let [db (db-with-data)]
    (is (= #{{:id 10 :cust-id 1 :status :shipped} {:id 11 :cust-id 1 :status :open}}
           (get (core/index db :aorder/by-cust-id) 1)))))

(deftest a-composite-index-reads-as-a-nested-map
  (s/create-index :aorder [:cust-id :status])
  (let [db (db-with-data)]
    (is (= #{{:id 10 :cust-id 1 :status :shipped}}
           (get-in (core/index db :aorder/by-cust-id+status) [1 :shipped])))
    ;; the issue's shape: take n of a nested path without scanning the table
    (is (= [{:id 10 :cust-id 1 :status :shipped}]
           (into [] (take 1) (get-in (core/index db :aorder/by-cust-id+status)
                                     [1 :shipped])))))
  (s/forget-index :aorder/by-cust-id+status))

(deftest indexes-stay-correct-across-transactions
  (let [db (db-with-data)]
    (is (= {:id 1 :name "ada" :email "a@x"} (get-in (core/index db :acustomer/by-id) [1])))
    (let [db' (core/update db :acustomer [= :id 1] #(assoc % :name "grace"))]
      (is (= {:id 1 :name "grace" :email "a@x"} (get-in (core/index db' :acustomer/by-id) [1])))
      (is (= {:id 1 :name "ada" :email "a@x"} (get-in (core/index db :acustomer/by-id) [1]))
          "the old db still sees the old value"))))

(deftest an-unknown-index-name-throws
  (let [db (db-with-data)]
    (try
      (core/index db :aorder/by-nope)
      (is false "expected the read to be rejected")
      (catch Throwable t
        (let [d (ex-data t)]
          (is (= :unknown-index (:relatum/error d)))
          (is (contains? (set (:known d)) :aorder/by-cust-id)))))))

(deftest a-fresh-db-has-empty-indexes-not-missing-ones
  (is (= {} (core/index (core/create-db) :aorder/by-cust-id))))

(deftest an-index-always-reads-as-a-map-never-nil
  ;; empty-but-materialized reads {}; unknown names throw - there is no
  ;; not-found case in between
  (let [db (core/create-db)]
    (is (map? (core/index db :aorder/by-cust-id)))
    (is (= {} (core/index db :aorder/by-cust-id)))
    (is (thrown-with-msg? Throwable #"Unknown index"
                          (core/index db :aorder/by-nope)))))

;; ---------------------------------------------------------------------------
;; edge: a read never rebuilds

(deftest reading-an-index-the-db-does-not-keep-is-rejected
  ;; a read is a lookup, never a hidden rebuild: a rebuilt map would not be
  ;; maintained, so it would go stale on the next transaction. Installing is
  ;; explicit - see ensure-index - and returns the db that keeps it.
  (let [db (db-with-data)
        _ (s/create-index :aorder [:status])]
    (try
      (core/index db :aorder/by-status)
      (is false "expected the read to be rejected")
      (catch Throwable t
        (let [d (ex-data t)]
          (is (= :index-not-materialized (:relatum/error d)))
          (is (= :aorder/by-status (:index d)))))
      (finally (s/forget-index :aorder/by-status)))))

(deftest ensure-index-installs-a-late-declared-index-and-keeps-it-current
  (let [db (db-with-data)
        _ (s/create-index :aorder [:status])
        db' (core/ensure-index db :aorder/by-status)]
    (is (= #{{:id 10 :cust-id 1 :status :shipped} {:id 12 :cust-id 2 :status :shipped}}
           (get (core/index db' :aorder/by-status) :shipped)))
    ;; the installed db maintains the index; the original does not
    (let [db'' (core/insert db' :aorder {:id 13 :cust-id 2 :status :shipped})]
      (is (= 3 (count (get (core/index db'' :aorder/by-status) :shipped))))
      (is (= 2 (count (get (core/index db' :aorder/by-status) :shipped)))))
    (s/forget-index :aorder/by-status)))

;; ---------------------------------------------------------------------------
;; edge: a unique or key column that is also a reference keeps the unique shape

(s/create-table :auniq-parent [[:id :int :primary :key]])
(s/create-table :auniq-child [[:id :int :primary :key]
                              [:pid :int :unique :references :auniq-parent]])
(s/create-table :apkfk-child [[:pid :int :primary :key :references :auniq-parent]
                              [:v :int]])

(deftest a-unique-fk-column-derives-one-index-and-it-is-unique
  (is (= [{:name :auniq-child/by-id :table :auniq-child :cols [:id]
           :type :unique :origin :pk}
          {:name :auniq-child/by-pid :table :auniq-child :cols [:pid]
           :type :unique :origin :unique}]
         (plan/derived-indexes (s/table :auniq-child)))))

(deftest a-key-column-that-is-also-a-reference-keeps-its-unique-index
  (is (= [{:name :apkfk-child/by-pid :table :apkfk-child :cols [:pid]
           :type :unique :origin :pk}]
         (plan/derived-indexes (s/table :apkfk-child)))))

(deftest a-unique-fk-column-reads-as-key-to-row
  (let [db (-> (core/create-db)
               (core/insert :auniq-parent {:id 1})
               (core/insert :auniq-child {:id 10 :pid 1}))]
    (is (= {:id 10 :pid 1} (get-in (core/index db :auniq-child/by-pid) [1])))))

(deftest a-key-fk-column-reads-as-key-to-row
  (let [db (-> (core/create-db)
               (core/insert :auniq-parent {:id 1})
               (core/insert :apkfk-child {:pid 1 :v 2}))]
    (is (= {:pid 1 :v 2} (get-in (core/index db :apkfk-child/by-pid) [1])))))

;; ---------------------------------------------------------------------------
;; edge: fk fast path must not weaken the foreign key check

(s/create-table :aorphan-parent [[:id :int :primary :key] [:code :int]])
(s/create-table :aorphan-child [[:id :int :primary :key]
                                [:pid :int :references :aorphan-parent]
                                [:code :int :nullable]
                                [:pcode :int :nullable :references :aorphan-parent]])

(deftest an-fk-fast-path-still-rejects-a-missing-parent
  ;; pcode names a real parent, so the only violation is pid -> 99
  (let [db (-> (core/create-db)
               (core/insert :aorphan-parent {:id 1 :code 7}))]
    (is (thrown-with-msg? Throwable #"Foreign key violation"
                          (core/insert db :aorphan-child {:id 10 :pid 99 :code nil :pcode 1})))))

(deftest a-present-parent-passes-the-fast-path
  (let [db (-> (core/create-db)
               (core/insert :aorphan-parent {:id 1 :code 7})
               (core/insert :aorphan-child {:id 10 :pid 1 :code nil :pcode 1}))]
    (is (= 1 (count (core/rows db :aorphan-child))))))

;; ---------------------------------------------------------------------------
;; edge: the insert-path fallback when the pk index is not materialized

(deftest an-unkept-pk-index-still-checks-references-on-insert
  ;; table-constraints installs the declared constraints but no access
  ;; indexes, so every parent lookup here takes the fallback branch
  (let [db (-> {} (core/table-constraints :auniq-parent)
               (core/table-constraints :auniq-child))]
    (testing "a row whose parent exists goes in"
      (is (some? (-> db
                     (core/insert :auniq-parent {:id 1})
                     (core/insert :auniq-child {:id 10 :pid 1})))))
    (testing "a dangling parent is still rejected"
      (is (thrown-with-msg? Throwable #"Foreign key violation"
                            (core/insert db :auniq-child {:id 11 :pid 99}))))))

(deftest ensure-index-rejects-an-unknown-name
  (is (thrown-with-msg? Throwable #"Unknown index"
                        (core/ensure-index (core/create-db) :aorder/by-nope))))
