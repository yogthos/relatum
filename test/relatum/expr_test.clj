(ns relatum.expr-test
  "The value-level semantics every expression composes through. These are the
  operators the planner emits (:and/:or/:not/:if and the ordered comparisons),
  so getting them wrong makes a constraint that reads correctly accept the
  wrong rows."
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.expr :as expr]
            [relatum.engine.expr :as eng]))

(deftest ordered-comparisons-follow-compare
  (testing "numbers"
    (is (true? (expr/lt 1 2)))
    (is (false? (expr/lt 2 1)))
    (is (false? (expr/lt 2 2)))
    (is (true? (expr/lte 2 2)))
    (is (true? (expr/gt 2 1)))
    (is (true? (expr/gte 2 2))))
  (testing "anything comparable, not just numbers"
    (is (true? (expr/lt "a" "b")))
    (is (true? (expr/gt :b :a)))
    (is (true? (expr/lt [1 2] [1 3])))))

(deftest the-engine-keeps-the-arity-the-expression-language-allows
  (testing "a single argument is vacuously ordered"
    (is (true? (eng/< 1)))
    (is (true? (eng/<= 1)))
    (is (true? (eng/> 1)))
    (is (true? (eng/>= 1))))
  (testing "two arguments are the pure semantics"
    (is (true? (eng/< 1 2)))
    (is (false? (eng/< 2 1)))
    (is (true? (eng/>= 1 1))))
  (testing "more than two chain"
    (is (true? (eng/< 1 2 3)))
    (is (false? (eng/< 1 3 2)))
    (is (true? (eng/<= 1 2 2)))
    (is (false? (eng/<= 1 2 1)))
    (is (true? (eng/> 3 2 1)))
    (is (false? (eng/> 3 1 2)))))

(deftest comparisons-are-mirror-images
  (doseq [[a b] [[1 2] [2 1] [2 2] ["a" "b"] ["b" "a"] [:a :b]]]
    (is (= (expr/lt a b) (expr/gt b a)) (str "lt/gt " a " " b))
    (is (= (expr/lte a b) (expr/gte b a)) (str "lte/gte " a " " b))
    (is (= (expr/lte a b) (not (expr/gt a b))) (str "lte/not-gt " a " " b))))

(deftest equality-is-clojure-equality
  (is (true? (expr/eq 1 1)))
  (is (false? (expr/eq 1 2)))
  (is (true? (expr/eq [1 2] [1 2])))
  (is (true? (expr/eq nil nil)))
  (is (true? (expr/eq :a :a))))

(deftest and-of-requires-every-value
  (is (true? (expr/and-of [])))
  (is (true? (expr/and-of [true true 1 "x"])))
  (is (false? (expr/and-of [true false])))
  (is (false? (expr/and-of [true nil])))
  (is (true? (expr/and-of [true])))
  (testing "a false stops the fold, so later values are not forced"
    (is (false? (expr/and-of (lazy-seq (cons false (lazy-seq (throw (ex-info "forced" {}))))))))))

(deftest or-of-returns-the-first-truthy-value
  (is (nil? (expr/or-of [])))
  (is (nil? (expr/or-of [nil false])))
  (is (true? (expr/or-of [nil true])))
  (is (= 42 (expr/or-of [nil 42 :ignored])))
  (is (= :a (expr/or-of [:a :b])))
  (testing "a truthy stops the fold, so later values are not forced"
    (is (= 1 (expr/or-of (lazy-seq (cons 1 (lazy-seq (throw (ex-info "forced" {}))))))))))

(deftest not-of-is-clojure-not
  (is (true? (expr/not-of nil)))
  (is (true? (expr/not-of false)))
  (is (false? (expr/not-of true)))
  (is (false? (expr/not-of 0))))

(deftest if-of-chooses-on-truthiness
  (is (= :yes (expr/if-of true :yes :no)))
  (is (= :no (expr/if-of false :yes :no)))
  (is (= :no (expr/if-of nil :yes :no)))
  (is (= :yes (expr/if-of 0 :yes :no)))
  (is (nil? (expr/if-of false :yes nil))))
