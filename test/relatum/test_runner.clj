(ns relatum.test-runner
  (:require [clojure.test :as t]
            [relatum.engine-test]
            [relatum.expr-test]
            [relatum.core-test]
            [relatum.integrity-test]
            [relatum.query-test]
            [relatum.regression-test]
            [relatum.schema-test]
            [relatum.table-test]
            [relatum.relationship-test]
            [relatum.prop-test]))

(def ^:private fast-namespaces
  ['relatum.expr-test 'relatum.query-test 'relatum.integrity-test 'relatum.core-test 'relatum.engine-test
   'relatum.regression-test
   'relatum.schema-test 'relatum.table-test 'relatum.relationship-test])

(def ^:private all-namespaces
  (conj fast-namespaces 'relatum.prop-test))

(defn -main [& args]
  (let [nss (if (some #{"--all"} args) all-namespaces fast-namespaces)
        {:keys [fail error] :as r} (apply t/run-tests nss)]
    (println "SUMMARY" (pr-str r))
    (System/exit (if (zero? (+ fail error)) 0 1))))
