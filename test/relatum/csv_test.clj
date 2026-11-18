(ns relatum.csv-test
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.core :as rc]
            [relatum.csv :as csv]
            [relatum.schema :as s]
            [relatum.type :as type]))

(defn- err [thunk]
  (try (thunk) nil
       (catch Throwable e
         (loop [e e]
           (cond (nil? e) nil
                 (:relatum/error (ex-data e)) (ex-data e)
                 :else (recur (ex-cause e)))))))

;; ---- reading a value from text ----------------------------------------------

(deftest read-value-per-kind
  (doseq [[t s v] [[:int "42" 42] [:int " -7 " -7]
                   [:real "1.5" 1.5] [:real "2" 2.0]
                   [:bool "true" true] [:bool "FALSE" false] [:bool "1" true] [:bool "0" false]
                   [:text " keep spaces " " keep spaces "] ["varchar(3)" "abcd" "abcd"]
                   [:keyword "active" :active] [:keyword ":active" :active] [:keyword "a/b" :a/b]
                   [:uuid "123e4567-e89b-12d3-a456-426614174000"
                    #uuid "123e4567-e89b-12d3-a456-426614174000"]
                   [:date "2024-01-02" (java.time.LocalDate/parse "2024-01-02")]
                   [:timestamp "2024-01-02T03:04:05Z" (java.time.Instant/parse "2024-01-02T03:04:05Z")]]]
    (is (= {:ok true :value v} (type/read-value (type/parse-type t) s)) (str t " " s))))

(deftest read-value-blank-and-nil
  (is (= {:ok true :value nil} (type/read-value (type/parse-type :int) "")))
  (is (= {:ok true :value nil} (type/read-value (type/parse-type :int) "  ")))
  (is (= {:ok true :value nil} (type/read-value (type/parse-type :text) nil)))
  (testing "an empty string is still text"
    (is (= {:ok true :value ""} (type/read-value (type/parse-type :text) "")))))

(deftest read-value-rejects-without-throwing
  (doseq [[t s] [[:int "4.2"] [:int "x"] [:real "abc"] [:bool "maybe"]
                 [:uuid "nope"] [:date "2024-13-45"] [:timestamp "yesterday"]]]
    (let [v (type/read-value (type/parse-type t) s)]
      (is (false? (:ok v)) (str t " " s))
      (is (= :unreadable (:error v))))))

(deftest read-value-passes-typed-values-through
  (is (= {:ok true :value 3} (type/read-value (type/parse-type :int) 3))))

;; ---- reading a row ---------------------------------------------------------

(s/create-table :csv-person
  [[:id      :int  :primary :key]
   [:name    :text]
   [:balance :real :default 0.0]
   [:active  :bool :nullable]
   [:doubled :real :as [* :balance 2]]])

(deftest read-row-types-each-column
  (is (= {:id 1 :name "Ada" :balance 10.5 :active true}
         (s/read-row :csv-person {"id" "1" "name" "Ada" "balance" "10.5" "active" "true"})))
  (testing "keyword keys work too"
    (is (= {:id 1} (s/read-row :csv-person {:id "1"}))))
  (testing "blank leaves the column out, so its default applies"
    (is (= {:id 1 :name "A"} (s/read-row :csv-person {"id" "1" "name" "A" "balance" ""}))))
  (testing "computed columns are ignored, they are derived on insert"
    (is (= {:id 1} (s/read-row :csv-person {"id" "1" "doubled" "99"})))))

(deftest read-row-rejections-name-the-column
  (let [d (err #(s/read-row :csv-person {"id" "one"}))]
    (is (= :unreadable (:relatum/error d)))
    (is (= :id (:column d)))
    (is (= "one" (:value d))))
  (is (= :unknown-column (:relatum/error (err #(s/read-row :csv-person {"nope" "1"}))))))

;; ---- the csv format -------------------------------------------------------

(deftest parse-handles-quotes-and-newlines
  (is (= [["a" "b" "c"] ["1" "2" "3"]] (csv/parse "a,b,c\n1,2,3")))
  (is (= [["a" "b"] ["1" "2"]] (csv/parse "a,b\r\n1,2\r\n")))
  (is (= [["x,y" "say \"hi\"" "two\nlines"]]
         (csv/parse "\"x,y\",\"say \"\"hi\"\"\",\"two\nlines\"")))
  (testing "an unquoted empty field is nil, a quoted one is the empty string"
    (is (= [["a" nil ""]] (csv/parse "a,,\"\""))))
  (testing "a trailing empty field counts"
    (is (= [["a" nil]] (csv/parse "a,"))))
  (is (= [] (csv/parse "")))
  (testing "another separator"
    (is (= [["a" "b"]] (csv/parse "a;b" {:separator \;})))))

(deftest parse-rejects-an-unterminated-quote
  (let [d (err #(csv/parse "a,\"open"))]
    (is (= :csv-unterminated-quote (:relatum/error d)))
    (is (= 1 (:line d)))))

(deftest write-quotes-only-when-needed
  (is (= "a,b\n1,\n" (csv/write [["a" "b"] [1 nil]])))
  (is (= "\"x,y\",\"say \"\"hi\"\"\",\"two\nlines\"\n"
         (csv/write [["x,y" "say \"hi\"" "two\nlines"]])))
  (is (= "a,\"\"\n" (csv/write [["a" ""]])))
  (testing "values print in the form read-value takes back"
    (is (= "active,1.5,true,2024-01-02\n"
           (csv/write [[:active 1.5 true (java.time.LocalDate/parse "2024-01-02")]])))
    (is (= "a/b\n" (csv/write [[:a/b]])))))

(deftest parse-undoes-write
  (let [rows [["a" "b,c" "d\"e" "f\ng" "" nil]]]
    (is (= rows (csv/parse (csv/write rows))))))

;; ---- tables ----------------------------------------------------------------

(deftest insert-loads-a-table
  (let [db (csv/insert (rc/create-db) :csv-person
                       "id,name,balance,active\n1,Ada,100,true\n2,Grace,,\n")]
    (is (= {:id 1 :name "Ada" :balance 100.0 :active true :doubled 200.0}
           (rc/row db :csv-person 1)))
    (is (= {:id 2 :name "Grace" :balance 0.0 :active nil :doubled 0.0}
           (rc/row db :csv-person 2)))))

(deftest insert-without-a-header
  (let [db (csv/insert (rc/create-db) :csv-person "1,Ada\n"
                       {:columns [:id :name]})]
    (is (= "Ada" (:name (rc/row db :csv-person 1))))))

(deftest a-bad-line-says-which-line-and-writes-nothing
  (let [d (err #(csv/insert (rc/create-db) :csv-person
                            "id,name\n1,Ada\nx,Bob\n"))]
    (is (= :unreadable (:relatum/error d)))
    (is (= 3 (:line d)))
    (is (= :id (:column d)))))

(deftest a-short-line-is-rejected
  (is (= :csv-width
         (:relatum/error (err #(csv/insert (rc/create-db) :csv-person "id,name\n1\n"))))))

(deftest export-round-trips
  (let [db (csv/insert (rc/create-db) :csv-person
                       "id,name,balance,active\n1,\"Ada, Countess\",100,true\n2,Grace,,\n")
        text (csv/export db :csv-person)]
    (testing "header in declared column order, rows by primary key"
      (is (= "id,name,balance,active,doubled\n1,\"Ada, Countess\",100.0,true,200.0\n2,Grace,0.0,,0.0\n"
             text)))
    (testing "and it loads back into the same rows"
      (is (= (set (rc/rows db :csv-person))
             (set (rc/rows (csv/insert (rc/create-db) :csv-person text) :csv-person)))))))

(deftest export-a-query
  (let [db (csv/insert (rc/create-db) :csv-person "id,name\n1,Ada\n2,Grace\n")]
    (is (= "name\nAda\nGrace\n"
           (csv/export db [[:from :csv-person] [:select :name]] {:columns [:name] :sort [:name]})))))
