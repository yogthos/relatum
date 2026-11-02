;; Bench relatum queries against the plain data-structure equivalents, to see
;; where the engine's overhead lands and whether tuning is warranted.
;; Run: jolt bench/bench.clj

(require '[relatum.engine :as rel]
         '[relatum.core :as rc]
         '[relatum.schema :as s])

(def N 10000)

;; ---- data -----------------------------------------------------------------

(def depts ["eng" "sales" "ops" "hr" "finance" "legal" "support" "it" "research" "marketing"])
(defn row [i] {:id i :dept (nth depts (mod i (count depts))) :salary (inc (mod i 97))})
(def rows (vec (map row (range N))))
(def dept-rows (vec (map (fn [i d] {:code d :floor (inc i)}) (range) depts)))

;; plain-structure equivalents; the index build cost sits outside the timing,
;; the same way rel/mat does
(def id->row (reduce (fn [m r] (assoc m (:id r) r)) {} rows))
(def dept->rows (group-by :dept rows))
(def floor-by-dept (reduce (fn [m d] (assoc m (:code d) (:floor d))) {} dept-rows))
(def row-set (set rows))

;; relatum dbs; materialization cost also outside the timing
(def db (rel/transact {} {:emp rows :dept dept-rows}))
(def db-unique (rel/mat db [[:from :emp] [:unique :id]]))
(def db-hash (rel/mat db [[:from :emp] [:hash :dept]]))
(def db-agg (rel/mat db [[:from :emp] [:agg [] [:total [rel/sum :salary]]]]))
(def db-join (rel/mat db [[:from :emp] [:join [[:from :dept]] {:dept :code}]]))

(def q-id [[:from :emp] [:where [= :id 5000]]])
(def q-dept [[:from :emp] [:where [= :dept "eng"]]])
(def q-sum [[:from :emp] [:agg [] [:total [rel/sum :salary]]]])
(def q-join [[:from :emp] [:join [[:from :dept]] {:dept :code}]])

;; ---- sanity: both sides must answer the same thing -------------------------

(def failures (atom 0))
(defn check [label actual expected]
  (when-not (= actual expected)
    (swap! failures inc)
    (println "FAIL" label "| expected" (pr-str expected) "| actual" (pr-str actual))))

(check "unique lookup" (first (rel/q db-unique q-id)) (get id->row 5000))
(check "cold dept count" (count (rel/q db q-dept)) (count (get dept->rows "eng")))
(check "hash dept count" (count (rel/q db-hash q-dept)) (count (get dept->rows "eng")))
(check "cold sum" (:total (first (rel/q db q-sum))) (reduce + (map :salary rows)))
(check "mat sum" (:total (first (rel/q db-agg q-sum))) (reduce + (map :salary rows)))
(check "cold join count" (count (rel/q db q-join)) N)
(check "mat join count" (count (rel/q db-join q-join)) N)
(check "engine insert"
       (count (rel/q (rel/transact db [:insert :emp {:id (+ N 900000) :dept "eng" :salary 1}])
                     [[:from :emp] [:where [= :id (+ N 900000)]]]))
       1)

(s/create-table* :bench-emp [[:id :int :primary :key] [:dept :text] [:salary :int]])
(check "rc insert"
       (count (rc/rows (rc/insert (rc/create-db) :bench-emp {:id 1 :dept "eng" :salary 1}) :bench-emp))
       1)

;; ---- harness ---------------------------------------------------------------

(defn- now [] (System/nanoTime))

(defn- round1 [x] (/ (long (+ 0.5 (* 10.0 x))) 10.0))
(defn- round2 [x] (/ (long (+ 0.5 (* 100.0 x))) 100.0))

(defn- timed [f iters]
  (let [t0 (now)]
    (dotimes [_ iters] (f))
    (- (now) t0)))

;; aim for ~300ms per measured round, capped so trivial ops don't run forever
(defn- auto-iters [f]
  (let [t0 (now)]
    (f)
    (let [per (- (now) t0)]
      (if (< per 1000)
        300000
        (max 1 (min 300000 (long (/ 3.0e8 per))))))))

(def results (atom {}))

(defn bench [k label f]
  (dotimes [_ 3] (f))
  (let [iters (auto-iters f)
        best (apply min (repeatedly 3 #(timed f iters)))
        ns (round1 (/ best iters 1.0))]
    (swap! results assoc k ns)
    (println label "\t" ns "ns/op\t" (long (/ 1.0e9 ns)) "ops/s")))

(def insert-i (atom 0))
(defn- fresh-row []
  (let [i (swap! insert-i inc)]
    {:id (+ N 950000 i) :dept "eng" :salary 50}))

(def vdb (rc/create-db))

;; ---- reads -----------------------------------------------------------------

(println "N =" N "rows")
(println)
(println "---- reads ----")
(bench :plain-get     "plain    map get by id      " #(get id->row 5000))
(bench :plain-scan    "plain    vector scan by id  " #(some (fn [r] (= (:id r) 5000)) rows))
(bench :cold-id       "relatum  cold lookup by id  " #(rel/q db q-id))
(bench :mat-id        "relatum  mat unique by id  " #(rel/q db-unique q-id))
(println)
(bench :plain-bucket  "plain    bucket map by dept " #(get dept->rows "eng"))
(bench :plain-filter  "plain    filter by dept     " #(count (filter (fn [r] (= (:dept r) "eng")) rows)))
(bench :cold-dept     "relatum  cold filter by dept" #(count (rel/q db q-dept)))
(bench :mat-dept      "relatum  mat hash by dept   " #(count (rel/q db-hash q-dept)))
(println)
(bench :plain-sum     "plain    reduce + salaries  " #(reduce + (map :salary rows)))
(bench :cold-sum      "relatum  cold agg sum       " #(rel/q db q-sum))
(bench :mat-sum       "relatum  mat agg sum        " #(rel/q db-agg q-sum))
(println)
(bench :plain-join    "plain    map-assoc join     " #(count (map (fn [e] (assoc e :floor (get floor-by-dept (:dept e)))) rows)))
(bench :cold-join     "relatum  cold join          " #(count (rel/q db q-join)))
(bench :mat-join      "relatum  mat join           " #(count (rel/q db-join q-join)))

;; ---- writes ----------------------------------------------------------------

(println)
(println "---- writes (single row) ----")
(bench :plain-conj     "plain    conj onto vector   " #(conj rows (fresh-row)))
(bench :plain-conj-set "plain    conj onto set      " #(conj row-set (fresh-row)))
(bench :ins-none       "engine   insert, no index  " #(rel/transact db [:insert :emp (fresh-row)]))
(bench :ins-unique     "engine   insert + unique   " #(rel/transact db-unique [:insert :emp (fresh-row)]))
(bench :ins-agg        "engine   insert + mat agg  " #(rel/transact db-agg [:insert :emp (fresh-row)]))
(bench :ins-rc         "rc       insert validated  " #(rc/insert vdb :bench-emp (fresh-row)))

;; ---- summary ---------------------------------------------------------------

(defn- x [krel kplain]
  (round2 (/ (get @results krel) (get @results kplain) 1.0)))

(println)
(println "relatum cost as a multiple of the plain equivalent (lower is better):")
(println " point lookup by id : mat" (x :mat-id :plain-get) "x map get | cold" (x :cold-id :plain-scan) "x vector scan")
(println " filter by dept     : mat" (x :mat-dept :plain-bucket) "x bucket map | cold" (x :cold-dept :plain-filter) "x filter")
(println " sum salaries       : mat" (x :mat-sum :plain-sum) "x reduce | cold" (x :cold-sum :plain-sum) "x")
(println " join dept floor    : mat" (x :mat-join :plain-join) "x map-assoc | cold" (x :cold-join :plain-join) "x")
(println " insert one row     : bare" (x :ins-none :plain-conj) "x conj | +unique" (x :ins-unique :plain-conj-set) "x set conj | rc" (x :ins-rc :plain-conj-set) "x")

(println)
(if (pos? @failures)
  (do (println @failures "SANITY FAILURES - the numbers above are meaningless")
      (System/exit 1))
  (println "sanity ok: both sides computed the same answers"))
