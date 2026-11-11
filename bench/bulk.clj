;; Bench bulk loads and indexed lookups through relatum.core: the paths an
;; index-shaped consumer (many tables, FK cascades, tens of thousands of rows)
;; leans on. Run: jolt bench/bulk.clj [N]   (default N = 5000)
;;
;; New APIs (rc/insert-tables, rc/lookup) are resolved at runtime so the script also
;; runs against an older relatum for a before/after comparison.

(require '[relatum.engine :as rel]
         '[relatum.core :as rc]
         '[relatum.schema :as s])

(def N (if-let [a (first *command-line-args*)] (Long/parseLong a) 5000))

(s/create-table* :bulk-file [[:id :int :primary :key :auto]
                             [:path :text :required]])
(s/create-table* :bulk-var [[:id :int :primary :key :auto]
                            [:file-id :int :references :bulk-file :on-delete :cascade]
                            [:name :text]
                            [:kind :keyword :default :defn]])
(s/create-table* :bulk-ref [[:id :int :primary :key]
                            [:file-id :int :references :bulk-file :on-delete :cascade]
                            [:var-id :int :references :bulk-var :on-delete :cascade]
                            [:line :int]])

(def n-files (max 1 (quot N 50)))
(def files (vec (for [i (range n-files)] {:path (str "src/f" i ".clj")})))
(def vars  (vec (for [i (range N)] {:file-id (inc (mod i n-files)) :name (str "v" i)})))
(def refs  (vec (for [i (range N)] {:id (inc i) :file-id (inc (mod i n-files))
                                    :var-id (inc (mod (* 7 i) N)) :line i})))

(defn- now [] (System/nanoTime))
(defmacro timed [label & body]
  `(let [t0# (now)
         r# (do ~@body)]
     (println (format "%-44s %10.1f ms" ~label (/ (- (now) t0#) 1.0e6)))
     r#))

(println "N =" N "vars," N "refs," n-files "files")

(def db0 (rc/create-db))

(def db-many
  (timed "insert-many x3 (auto ids on files/vars)"
    (-> db0
        (rc/insert-many :bulk-file files)
        (rc/insert-many :bulk-var vars)
        (rc/insert-many :bulk-ref refs))))

(when-let [insert-tables (resolve 'relatum.core/insert-tables)]
  (timed "rc/insert-tables {table rows} (one tx)"
    (insert-tables db0 {:bulk-ref refs :bulk-var vars :bulk-file files})))

(def engine-db
  (timed "rel/transact {table rows} (no validation)"
    (rel/transact db0 {:bulk-file (vec (rc/rows db-many :bulk-file))
                       :bulk-var (vec (rc/rows db-many :bulk-var))
                       :bulk-ref refs})))

(assert (= N (count (rc/rows db-many :bulk-ref))))
(assert (= N (count (rc/rows db-many :bulk-var))))

;; ---- reads ------------------------------------------------------------------

(def reps 2000)
(defn- per-op [label f]
  (f)
  (let [t0 (now)]
    (dotimes [i reps] (f i))
    (println (format "%-44s %10.1f us/op" label (/ (- (now) t0) reps 1.0e3)))))

(per-op "cold rc/q [:where [= :file-id x]]"
        (fn ([] nil) ([i] (rc/q db-many [[:from :bulk-ref] [:where [= :file-id (inc (mod i n-files))]]]))))
(per-op "get (rc/index :bulk-ref/by-file-id) x"
        (fn ([] nil) ([i] (get (rc/index db-many :bulk-ref/by-file-id) (inc (mod i n-files))))))
(when-let [lookup (resolve 'relatum.core/lookup)]
  (per-op "rc/lookup :bulk-ref :file-id x"
          (fn ([] nil) ([i] (lookup db-many :bulk-ref :file-id (inc (mod i n-files))))))
  (per-op "rc/lookup :bulk-ref :line x (no index)"
          (fn ([] nil) ([i] (lookup db-many :bulk-ref :line (mod i N))))))
