(ns relatum.spec-runner
  "Runs every writ spec under spec/ and exits non-zero unless all of them
  report :ok."
  (:require [relatum.expr-spec]
            [relatum.plan-spec]
            [relatum.query-spec]
            [relatum.type-spec]
            [writ.spec :as spec]))

(def ^:private specs ['relatum.type-spec 'relatum.plan-spec 'relatum.expr-spec 'relatum.query-spec])

(defn- summarize [laws]
  (let [proved (filter #(= :proved (:status %)) laws)
        tested (remove #(= :proved (:status %)) laws)]
    (println (str "       " (count proved) " proved, " (count tested) " tested"))
    (doseq [{:keys [law status unproved]} tested]
      (println (str "       - " law " " (name status) " (" (or unproved "no reason given") ")")))
    ;; A tested law was only exercised on generated inputs; a passing run is not
    ;; a proof, and this exit status says nothing about the tested ones.
    (when (seq tested)
      (println (str "       NOTE: " (count tested) " law(s) are property-checked, not proved."
                    " They hold for the inputs tried, not for all inputs.")))))

(defn- report [name r]
  (println (str (if (:ok r) "ok   " "FAIL ") name))
  (summarize (:laws r))
  (doseq [g (:gaps r)] (println "        gap:" (:fn g)))
  (when-not (:ok r)
    (when-let [m (:message r)] (println m))))

(defn -main [& _]
  (let [results (mapv (fn [s] [s (spec/check s)]) specs)]
    (doseq [[s r] results] (report s r))
    (System/exit (if (every? (comp :ok second) results) 0 1))))
