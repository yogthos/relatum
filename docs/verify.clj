;; Verify every example in docs/. Run: jolt docs/verify.clj
;; Prints one line per check and FAILs loudly.
(require '[relatum.schema :as s]
         '[relatum.core :as rc]
         '[relatum.engine :as rel])

(def failures (atom 0))

(defn check [label actual expected]
  (let [ok (= actual expected)]
    (when-not ok (swap! failures inc))
    (println (str (if ok "ok  " "FAIL ") label
                  (when-not ok
                    (str "\n     expected: " (pr-str expected)
                         "\n     actual:   " (pr-str actual)))))))

(defmacro throws [label re & body]
  `(try
     (do ~@body)
     (swap! failures inc)
     (println (str "FAIL " ~label " (did not throw)"))
     (catch Throwable t#
       (let [m# (re-find ~re (str t#))]
         (if m#
           (println (str "ok   " ~label))
           (do (swap! failures inc)
               (println (str "FAIL " ~label "\n     got: " t#))))))))

;; ---- tables.md ----------------------------------------------------------

(check "column-names"
       (do (s/create-table* :vperson
             [[:id :int :primary :key]
              [:name "varchar(64)" :required]
              [:balance :real :default 0.0]])
           (s/column-names :vperson))
       [:id :name :balance])

(check "record skipped with :record false"
       (do (s/create-table :vtag [[:id :int :primary :key] [:label :text]]
             :record false)
           (s/column-names :vtag))
       [:id :label])

(check "default fills an absent column"
       (:balance (s/coerce-row (s/table :vperson) {:id 1 :name "a"}))
       0.0)

(check ":auto recorded"
       (do (s/create-table* :vauto [[:id :int :primary :key :auto] [:v :text]])
           (:auto? (-> (s/table :vauto) :columns first)))
       true)

(check "describe is plain data"
       (get-in (s/describe :vperson) [:columns 1 :type])
       "varchar(64)")

(check "ddl renders"
       (boolean (re-find #"create-table :vperson" (s/ddl :vperson)))
       true)

(check "build-table* validates without registering"
       (:name (s/build-table* :vtemp [[:id :int :primary :key]]))
       :vtemp)

(throws "truncated :references rejects"
        #"needs a value"
        (s/build-table* :vbad [[:pid :int :references]]))

(throws "nil default rejects"
        #"needs a value"
        (s/build-table* :vbad2 [[:x :text :default nil]]))

(throws "nullable primary key rejects"
        #"cannot be nullable"
        (s/build-table* :vbad3 [[:id :int :primary :key :nullable]]))

(throws "duplicate column rejects"
        #"Duplicate"
        (s/build-table* :vbad4 [[:id :int] [:id :text]]))

;; ---- types.md -----------------------------------------------------------

(check "varchar bound"
       (:max-length (s/parse-type "varchar(32)"))
       32)

(check "kind"
       (:kind (s/parse-type :int))
       :int)

(check "known types"
       (s/known-types)
       ["bool" "date" "int" "keyword" "real" "text" "timestamp" "uuid" "varchar"])

(check "real doubles"
       (s/coerce-row (s/build-table* :vr [[:id :int :primary :key] [:x :real]])
                     {:id 1 :x 3})
       {:id 1 :x 3.0})

(throws "bad type message"
        #"expects real"
        (s/coerce-row (s/table :vperson) {:id 1 :name "a" :balance "many"}))

(throws "varchar too long"
        #"exceeds 64"
        (s/coerce-row (s/table :vperson) {:id 1 :name (apply str (repeat 65 "x"))}))

(throws "date is the object, not the string"
        #"expects date"
        (s/coerce-row (s/build-table* :vd [[:id :int :primary :key] [:d :date]])
                      {:id 1 :d "2026-01-01"}))

(check "conforms?"
       [(s/conforms? (s/parse-type :int) 3)
        (s/conforms? (s/parse-type :int) 3.5)
        (s/varchar-conforms? 4 "abcd")
        (s/varchar-conforms? 4 "abcde")]
       [true false true false])

;; ---- queries.md ---------------------------------------------------------

(def sale-db
  (-> {}
      (rel/transact [:insert :sale {:dept "toys" :amt 3}])
      (rel/transact [:insert :sale {:dept "toys" :amt 5}])
      (rel/transact [:insert :sale {:dept "food" :amt 2}])))

(check "grand total"
       (rel/q sale-db [[:from :sale] [:agg [] [:total [rel/sum :amt]]]])
       '({:total 10}))

(check "grouped agg"
       (sort-by :dept
                (rel/q sale-db
                  [[:from :sale]
                   [:agg [:dept] [:total [rel/sum :amt]]
                         [:best [rel/min-by :amt]]
                         [:n count]]]))
       '({:dept "food" :n 1 :total 2 :best {:dept "food" :amt 2}}
         {:dept "toys" :n 2 :total 8 :best {:dept "toys" :amt 3}}))

(check "count-distinct"
       (rel/q sale-db [[:from :sale] [:agg [] [:ds [rel/count-distinct :dept]]]])
       '({:ds 2}))

(check "const literal relation"
       (rel/q {} [[:const [{:a 1} {:a 2}]] [:where [> :a 1]]])
       '({:a 2}))

(check "select returns keys in selection order"
       (mapv #(vec (keys %)) (rel/q sale-db [[:from :sale] [:select :amt :dept]]))
       [[:amt :dept] [:amt :dept] [:amt :dept]])

(check "docs: select key order example"
       (vec (keys (first (rel/q sale-db [[:from :sale] [:select :amt :dept]]))))
       [:amt :dept])

(check "docs: computed selection keeps its written position"
       (vec (keys (first (rel/q sale-db [[:from :sale] [:select :dept [:double [* :amt 2]] :amt]]))))
       [:dept :double :amt])

(check "xf and into"
       (rel/q (rel/transact {} [:insert :A {:a 1} {:a 2}])
              [[:from :A]] {:xf (map :a) :into []})
       [1 2])

(check "sort"
       (rel/q (rel/transact {} [:insert :A {:a 2} {:a 1}])
              [[:from :A]] {:sort [:a]})
       '({:a 1} {:a 2}))

(check "rename"
       (rel/q (rel/transact {} [:insert :A {:a 1}])
              [[:from :A] [:rename {:a :b}]])
       '({:b 1}))

(check "qualify"
       (rel/q (rel/transact {} [:insert :A {:a 1}])
              [[:from :A] [:qualify "t"]])
       '(#:t{:a 1}))

(check "str extend"
       (rel/q (rel/transact {} [:insert :p {:first "ada" :last "l"}])
              [[:from :p] [:extend [:name [str :first " " :last]]]])
       '({:first "ada", :last "l", :name "ada l"}))

(def cust-db
  (rel/transact {}
    [:insert :order {:id 1 :cust-id 7 :total 3}]
    [:insert :customer {:id 7 :name "ada"}]))

(check "join"
       (rel/q cust-db [[:from :order] [:join [[:from :customer]] {:cust-id :id}]])
       '({:id 7, :cust-id 7, :total 3, :name "ada"}))

(check "sel1"
       (rel/q cust-db [[:from :customer]
                       [:extend [:first-order [rel/sel1 :order {:id :cust-id}]]]])
       '({:id 7, :name "ada", :first-order {:id 1, :cust-id 7, :total 3}}))

(check "env"
       (rel/q (rel/with-env cust-db {:now 99})
              [[:from :customer] [:extend [:now [rel/env :now]]]])
       '({:id 7, :name "ada", :now 99}))

(check "exists?"
       [(rel/exists? cust-db [[:from :customer]] [= :name "ada"])
        (rel/exists? cust-db [[:from :customer]] [= :name "nope"])]
       [true false])

(check "row"
       (rel/row cust-db [[:from :customer]] [= :id 7])
       {:id 7 :name "ada"})

(check "what-if"
       (rel/what-if cust-db [[:from :customer]]
                    [:insert :customer {:id 8 :name "grace"}])
       '({:id 8, :name "grace"} {:id 7, :name "ada"}))

(check "chained comparison, column first"
       (rel/q {} [[:const [{:a 1} {:a 2} {:a 3}]] [:where [<= :a 2 3]]])
       '({:a 1} {:a 2}))

(check "range is spelled :and"
       (rel/q {} [[:const [{:a 1} {:a 2} {:a 3}]] [:where [:and [>= :a 2] [<= :a 3]]]])
       '({:a 2} {:a 3}))

(check "union"
       (set (rel/q {} [[:const [{:a 42}]] [:union [[:const [{:a 42} {:b 1}]]]]]))
       '#{{:a 42} {:b 1}})

(check "intersection"
       (rel/q {} [[:const [{:a 42}]] [:intersection [[:const [{:a 42} {:b 1}]]]]])
       '({:a 42}))

(check "difference"
       (set (rel/q {} [[:const [{:a 42}]] [:difference [[:const [{:a 42} {:b 1}]]]]]))
       #{})

(check "track-transact"
       (:changes (rel/track-transact
                  (rel/watch cust-db [[:from :customer]])
                  [:insert :customer {:id 8 :name "grace"}]))
       {[[:from :customer]] {:added [{:id 8, :name "grace"}] :deleted []}})

;; ---- queries.md: nested access -------------------------------------------

(s/create-table :vcust [[:id :int :primary :key] [:name :text]])
(s/create-table :vorder [[:id :int :primary :key]
                         [:cust-id :int :references :vcust]
                         [:status :keyword]
                         [:created :int]])
(s/create-table :vuniq-child [[:id :int :primary :key]
                              [:pid :int :unique :references :vcust]])
(s/create-index :vorder [:cust-id :status])
(s/create-index :vorder [:created] :type :btree)

(check "pk index reads as id to row"
       (let [db (-> (rc/create-db)
                    (rc/insert-many :vcust [{:id 1 :name "ada"} {:id 2 :name "bob"}]))]
         (get-in (rc/index db :vcust/by-id) [2]))
       {:id 2, :name "bob"})

(check "fk index reads as id to rows"
       (let [db (-> (rc/create-db)
                    (rc/insert :vcust {:id 1 :name "ada"})
                    (rc/insert-many :vorder [{:id 10 :cust-id 1 :status :shipped :created 500}
                                             {:id 11 :cust-id 1 :status :open :created 1000}]))]
         (count (get (rc/index db :vorder/by-cust-id) 1)))
       2)

(check "declared composite index reads as nested map"
       (let [db (-> (rc/create-db)
                    (rc/insert-many :vcust [{:id 1 :name "ada"} {:id 2 :name "bob"}])
                    (rc/insert-many :vorder [{:id 10 :cust-id 1 :status :shipped :created 500}
                                             {:id 11 :cust-id 1 :status :open :created 1000}
                                             {:id 12 :cust-id 2 :status :shipped :created 1500}]))]
         [(count (get-in (rc/index db :vorder/by-cust-id+status) [1 :shipped]))
          (map :id (sort-by :id (take 2 (get (rc/index db :vorder/by-cust-id) 1))))])
       [1 '(10 11)])

(check "indexes follow the transaction"
       (let [db (-> (rc/create-db)
                    (rc/insert :vcust {:id 1 :name "ada"}))
             db' (rc/insert db :vcust {:id 2 :name "bob"})]
         [(count (rc/index db :vcust/by-id))
          (count (rc/index db' :vcust/by-id))])
       [1 2])

(check "a btree index reads in range order"
       (let [db (-> (rc/create-db)
                    (rc/insert :vcust {:id 1 :name "ada"})
                    (rc/insert-many :vorder [{:id 10 :cust-id 1 :status :shipped :created 500}
                                             {:id 11 :cust-id 1 :status :open :created 1000}
                                             {:id 12 :cust-id 1 :status :open :created 2000}]))]
         (map :id (mapcat val (subseq (rc/index db :vorder/by-created) >= 1000))))
       '(11 12))

(throws "an unknown index name is rejected"
        #"Unknown index"
        (rc/index (rc/create-db) :vorder/by-nope))

(check "a unique reference column reads as key to row"
       (let [db (-> (rc/create-db)
                    (rc/insert :vcust {:id 1 :name "ada"})
                    (rc/insert :vuniq-child {:id 10 :pid 1}))]
         (get-in (rc/index db :vuniq-child/by-pid) [1]))
       {:id 10, :pid 1})

(check "ensure-index installs a late-declared index and keeps it current"
       (let [db (-> (rc/create-db)
                    (rc/insert :vcust {:id 1 :name "ada"})
                    (rc/insert-many :vorder [{:id 10 :cust-id 1 :status :shipped :created 500}
                                             {:id 11 :cust-id 1 :status :open :created 1000}]))
             _ (s/create-index :vorder [:status])
             db' (rc/ensure-index db :vorder/by-status)]
         [(count (get (rc/index db' :vorder/by-status) :shipped))
          (count (get (rc/index (rc/insert db' :vorder
                                           {:id 12 :cust-id 1 :status :shipped :created 1200})
                                :vorder/by-status)
                      :shipped))])
       [1 2])

(throws "reading an unkept index is rejected, not rebuilt"
        #"not materialized"
        ;; a hand-built db keeps no access indexes; the read must not
        ;; quietly rebuild one
        (let [db (-> {} (rc/table-constraints :vcust) (rc/table-constraints :vorder))]
          (rc/index db :vorder/by-status)))

(s/forget-index :vorder/by-status)
(s/forget-index :vorder/by-cust-id+status)
(s/forget-index :vorder/by-created)
(s/forget :vuniq-child)
(s/forget :vcust)
(s/forget :vorder)

;; ---- writing-data.md ----------------------------------------------------

(check "insert validates and defaults"
       (do (s/create-table* :vperson2
             [[:id :int :primary :key]
              [:name "varchar(64)" :required]
              [:balance :real :default 0.0]])
           (rc/rows (-> (rc/create-db)
                        (rc/insert :vperson2 {:id 1 :name "Ada"})
                        (rc/insert :vperson2 {:id 2 :name "Grace" :balance 500.0}))
                    :vperson2))
       '[{:id 1, :name "Ada", :balance 0.0}
         {:id 2, :name "Grace", :balance 500.0}])

(throws "insert rejects unknown column"
        #"Unknown column"
        (rc/insert (rc/create-db) :vperson2 {:id 3 :name "X" :nope 1}))

(check "update via fn"
       (let [db (-> (rc/create-db)
                    (rc/insert :vperson2 {:id 1 :name "Ada"})
                    (rc/insert :vperson2 {:id 2 :name "Grace" :balance 500.0}))]
         (sort-by :id (rc/rows (rc/update db :vperson2 [= :id 1]
                                          (fn [row] (assoc row :balance 10.0)))
                               :vperson2)))
       '[{:id 1, :name "Ada", :balance 10.0}
         {:id 2, :name "Grace", :balance 500.0}])

(check "insert-or-replace with materialized unique"
       (set (rel/q (rel/transact (rel/mat {} [[:from :uor] [:unique :id]])
                                 [:insert-or-replace :uor {:id 42 :v 1}]
                                 [:insert-or-replace :uor {:id 42 :v 2}])
                   [[:from :uor]]))
       '#{{:id 42, :v 2}})

(check "map-form multi-table insert"
       (rel/q (rel/transact {} {:fruit [{:id 1 :name "apple"}] :veg [{:id 1}]})
              [[:from :fruit]])
       '({:id 1, :name "apple"}))

;; ---- constraints.md -----------------------------------------------------

(check "constraint ops shape"
       (do (s/create-table* :vfruit
             [[:name "varchar(8)" :primary :key]
              [:cost :int]
              [:grade :real]
              [:color :text :unique]])
           [(first (s/ddl->constraints (s/table :vfruit)))
            (boolean (some #(= [:unique :name] %) (s/ddl->constraints (s/table :vfruit))))
            (boolean (some #(= [:unique :color] %) (s/ddl->constraints (s/table :vfruit))))])
       [[:req :name :cost :grade :color] true true])

(defn- err-key [thunk]
  (try (thunk) nil
       (catch Throwable t
         (loop [e t]
           (cond (nil? e) nil
                 (:relatum/error (ex-data e)) (:relatum/error (ex-data e))
                 (:relatum.engine/error (ex-data e)) (:relatum.engine/error (ex-data e))
                 :else (recur (ex-cause e)))))))

(check "fk rejects dangling parent"
       (do (s/create-table* :vcust [[:id :int :primary :key] [:name :text]])
           (s/create-table* :vord [[:id :int :primary :key]
                                   [:cust-id :int :references :vcust]])
           (err-key #(rc/insert (-> {}
                                    (rc/table-constraints :vcust)
                                    (rc/table-constraints :vord))
                                :vord {:id 10 :cust-id 99})))
       :foreign-key-violation)

(check "cascade delete, the constraints.md snippet"
       (do (s/create-table* :vcaord [[:id :int :primary :key]
                                     [:cust-id :int :references :vcust :on-delete :cascade]])
           (let [db (-> {}
                        (rc/table-constraints :vcust)
                        (rc/table-constraints :vcaord)
                        (rc/insert :vcust {:id 1 :name "a"})
                        (rc/insert :vcaord {:id 10 :cust-id 1}))
                 after (rc/delete db :vcust)]
             [(count (rc/rows db :vcaord))
              (count (rc/rows after :vcust))    ;; parent gone
              (count (rc/rows after :vcaord))])) ;; children gone
       [1 0 0])

(check "readme orientation snippet"
       (let [r (rc/q (rc/insert (rc/create-db) :vperson2 {:id 1 :name "Ada"})
                     [[:from :vperson2] [:where [> :balance 100]]])]
         [(empty? r) (vec r)])
       [true []])

(check "fk op shape"
       (do (s/create-table* :vord2 [[:id :int :primary :key]
                                    [:cust-id :int :references :vcust]])
           (some #(= [:fk :vcust {:cust-id :id} nil] %)
                 (s/ddl->constraints (s/table :vord2))))
       true)

(check "identical duplicate insert is a no-op"
       (do (s/create-table* :vdup [[:id :int :primary :key] [:v :int]])
           [(count (rel/q (rel/mat {} (s/constraint-query (s/table :vdup)))
                          [[:from :vdup]]))
            (count (rel/q (rel/transact (rel/mat {} (s/constraint-query (s/table :vdup)))
                                        [:insert :vdup {:id 1 :v 1}]
                                        [:insert :vdup {:id 1 :v 1}])
                          [[:from :vdup]]))])
       [0 1])

(check "engine unique violation keyword"
       (do (s/create-table* :vuq [[:id :int :primary :key]])
           (err-key #(rel/transact (rel/mat {} (s/constraint-query (s/table :vuq)))
                                   [:insert :vuq {:id 1 :v 1}]
                                   [:insert :vuq {:id 1 :v 2}])))
       :unique-key-violation)

(check "engine check violation keyword"
       (do (s/create-table* :vck [[:id :int :primary :key] [:n :int]])
           (err-key #(rel/transact (rel/mat {} (s/constraint-query (s/table :vck)))
                                   [:insert :vck {:id 1 :n 1}]
                                   [:insert :vck {:id 2 :n "x"}])))
       :check-violation)

(check "check beyond DDL"
       (count (rel/q (rel/mat {}
                              [[:from :beyond] [:check {:pred [< :total 10000]
                                                        :error "order too large"}]])
                     [[:from :beyond]]))
       0)

;; ---- README.md ----------------------------------------------------------

(s/create-table* :rmperson
  [[:id :int :primary :key]
   [:name "varchar(64)" :required]
   [:balance :real :default 0.0]])

(def rmdb
  (-> (rc/create-db)
      (rc/insert :rmperson {:id 1 :name "Ada" :balance 100.0})
      (rc/insert :rmperson {:id 2 :name "Grace" :balance 500.0})
      (rc/insert :rmperson {:id 3 :name "Linus"})))

(check "readme: insert applies defaults"
       (set (rc/rows rmdb :rmperson))
       '#{{:id 1, :name "Ada", :balance 100.0}
          {:id 2, :name "Grace", :balance 500.0}
          {:id 3, :name "Linus", :balance 0.0}})

(check "readme: where equality"
       (rc/q rmdb [[:from :rmperson] [:where [= :name "Ada"]]])
       '({:id 1, :name "Ada", :balance 100.0}))

(check "readme: where then select"
       (set (rc/q rmdb [[:from :rmperson]
                        [:where [> :balance 50]]
                        [:select :name]]))
       '#{{:name "Ada"} {:name "Grace"}})

(check "readme: a range is spelled :and"
       (set (rc/q rmdb [[:from :rmperson]
                        [:where [:and [>= :balance 100] [<= :balance 500]]]]))
       '#{{:id 1, :name "Ada", :balance 100.0}
          {:id 2, :name "Grace", :balance 500.0}})

(check "readme: whole-table count"
       (rc/q rmdb [[:from :rmperson] [:agg [] [:n count]]])
       '({:n 3}))

(check "readme: a materialized aggregate answers and follows writes"
       (let [q [[:from :rmperson] [:agg [] [:total [rel/sum :balance]]]]
             mdb (rel/mat rmdb q)]
         [(rc/q mdb q)
          (rc/q (rc/insert mdb :rmperson {:id 4 :name "Alan" :balance 25.0}) q)])
       '[({:total 600.0}) ({:total 625.0})])

(check "readme: update through a fn of the old row"
       (rc/q (rc/update rmdb :rmperson [= :id 3]
                        (fn [row] (assoc row :balance 25.0)))
             [[:from :rmperson] [:where [= :id 3]]])
       '({:id 3, :name "Linus", :balance 25.0}))

(check "readme: delete clears the table"
       (rc/rows (rc/delete rmdb :rmperson) :rmperson)
       [])

(check "readme: a write leaves the database it came from untouched"
       (let [q [[:from :rmperson] [:agg [] [:n count]]]
             base (rc/insert rmdb :rmperson {:id 4 :name "Alan" :balance 25.0})
             branch (rc/insert base :rmperson {:id 5 :name "Radia" :balance 900.0})]
         [(rc/q base q)
          (rc/q branch q)
          ;; a second branch off base, not off branch — base must still have 4
          (rc/q (rc/insert base :rmperson {:id 6 :name "Kay" :balance 0.0}) q)])
       '[({:n 4}) ({:n 5}) ({:n 5})])

(check "readme: materialized views branch with the database"
       (let [q [[:from :rmperson] [:agg [] [:total [rel/sum :balance]]]]
             mdb (rel/mat rmdb q)
             b1 (rel/transact mdb [:insert :rmperson {:id 7 :name "A" :balance 1.0}])
             b2 (rel/transact mdb [:insert :rmperson {:id 8 :name "B" :balance 2.0}])]
         [(rc/q mdb q) (rc/q b1 q) (rc/q b2 q)])
        '[({:total 600.0}) ({:total 601.0}) ({:total 602.0})])

;; ---- README.md: graphs ---------------------------------------------------

(require '[relatum.graph :as g])

(s/create-table :rmfollows
  [[:src :text]
   [:dst :text]])

(def rmfollow-db
  (-> (rc/create-db)
      (rc/insert :rmfollows {:src "alice" :dst "bob"})
      (rc/insert :rmfollows {:src "bob" :dst "carol"})
      (rc/insert :rmfollows {:src "carol" :dst "alice"})))

(check "readme: graph-of builds a graph from a db table"
       (let [gr (g/graph-of rmfollow-db :rmfollows)]
         [(g/n-nodes gr)
          (set (g/neighbors gr "alice"))
          (g/degree gr "alice")
          (g/in-degree gr "carol")])
       [3 #{["bob" 1.0]} 1 1])

(check "readme: bfs and shortest-path decode node values"
       (let [gr (g/graph-of rmfollow-db :rmfollows)]
         [(set (g/bfs gr "alice"))
          (g/shortest-path gr "alice" "carol")])
       '[#{{:node "alice", :distance 0} {:node "bob", :distance 1} {:node "carol", :distance 2}}
         {:path ["alice" "bob" "carol"], :distance 2.0}])

(check "readme: weighted shortest-path prefers the cheap route"
       (let [w (g/graph [{:src :a :dst :b :weight 1.0}
                         {:src :b :dst :c :weight 1.0}
                         {:src :a :dst :c :weight 5.0}])]
         [(g/shortest-path w :a :c)
          (set (g/connected-components w))])
       '[{:path [:a :b :c], :distance 2.0}
         #{{:node :a, :component :a} {:node :b, :component :a} {:node :c, :component :a}}])

(check "readme: pagerank ranks sum to one"
       (let [w (g/graph [{:src :a :dst :b :weight 1.0}
                         {:src :b :dst :c :weight 1.0}
                         {:src :a :dst :c :weight 5.0}])
             ranks (map :rank (g/pagerank w :iters 30))]
         [(count ranks) (< 0.9 (reduce + ranks) 1.1)])
       [3 true])

;; ---- exit ---------------------------------------------------------------

(if (pos? @failures)
  (do (println "\n" @failures "FAILURES")
      (System/exit 1))
  (println "\nall doc examples verified"))
