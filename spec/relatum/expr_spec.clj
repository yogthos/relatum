(ns relatum.expr-spec
  "Laws for the value-level expression semantics: the ordered comparisons and
  the boolean composition every query and every emitted constraint is built
  from. If these are wrong, a constraint that reads correctly accepts the wrong
  rows, so the properties here are about meaning, not just well-behavedness."
  (:require [writ.spec :refer [spec ann law graph]]
            [relatum.expr]))

(spec relatum.expr)

;; --- signatures -----------------------------------------------------------

(ann truthy? [Any -> Bool])
(ann lt [Int Int -> Bool])
(ann lte [Int Int -> Bool])
(ann gt [Int Int -> Bool])
(ann gte [Int Int -> Bool])
(ann eq [Any Any -> Bool])
(ann and-of [(Vec Bool) -> Bool])
(ann or-of [(Vec Bool) -> (Opt Bool)])
(ann not-of [Bool -> Bool])
(ann if-of [Bool Int Int -> Int])

;; Three graphs, because a graph's state is one type: an ordering turns values
;; into a flag, boolean composition turns flags into a flag, and truth does the
;; Any-valued test that starts both. Splitting them keeps each fn's ann honest;
;; one graph over Any would force the comparison signatures up to Any and lose
;; what they say.

(graph truth
  {:start [:value 1]
   :states {:value Any, :flag Bool}
   :final [:flag]
   :edges {:value {[truthy?] #{:flag}
                   [eq Any] #{:flag}}
           :flag {[not-of] #{:flag}}}})

(graph ordering
  {:start [:number 1]
   :states {:number Int, :flag Bool}
   :final [:flag]
   :edges {:number {[lt Int] #{:flag}
                    [lte Int] #{:flag}
                    [gt Int] #{:flag}
                    [gte Int] #{:flag}}}})

(graph composition
  {:start [:flags [true]]
   :states {:flags (Vec Bool)
            :maybeflag (Opt Bool)
            :chosen Int
            :flag Bool}
   :final [:flag]
   :edges {:flags {[and-of] #{:flag}
                   [or-of] #{:maybeflag}}
           ;; or-of may find nothing, so its result is a value that still has
           ;; to be tested before it is a flag
           :maybeflag {[truthy?] #{:flag}}
           ;; if-of picks a branch, so it gives a value, and a value becomes a
           ;; flag only by a comparison
           :flag {[if-of Int Int] #{:chosen}}
           :chosen {[lt Int] #{:flag}}}})

;; --- truth ------------------------------------------------------------------

(law truth-is-exactly-not-nil-and-not-false
  (forall [_ Unit]
    (and (true? (truthy? 0))
         (true? (truthy? ""))
         (true? (truthy? ()))
         (false? (truthy? false))
         (false? (truthy? nil)))))

;; --- ordering ---------------------------------------------------------------
;; compare-based, so these hold for strings and keywords as well as numbers;
;; stating them on Int alone would miss a numeric comparison that never
;; consulted the general ordering.

(law lt-is-negative-compare
  (forall [a Int b Int]
    (= (lt a b) (neg? (compare a b)))))

(law lte-is-not-positive-compare
  (forall [a Int b Int]
    (= (lte a b) (not (pos? (compare a b))))))

(law lt-and-gt-are-mirror-images
  (forall [a Int b Int]
    (= (lt a b) (gt b a))))

(law lte-and-gte-are-mirror-images
  (forall [a Int b Int]
    (= (lte a b) (gte b a))))

(law lte-is-the-negation-of-gt
  (forall [a Int b Int]
    (= (lte a b) (not (gt a b)))))

(law a-total-order-has-no-surprises
  (forall [a Int b Int]
    (and (or (lt a b) (lt b a) (= a b))
         (not (and (lt a b) (lt b a))))))

;; --- boolean composition ---------------------------------------------------

(law and-of-holds-exactly-when-nothing-is-falsey
  (forall [vs (Vec Bool)]
    (and (= (and-of vs) (not-any? false? vs))
         ;; the empty conjunction is vacuously true
         (true? (and-of [])))))

(law or-of-is-truthy-exactly-when-something-is
  (forall [vs (Vec Bool)]
    (and (= (truthy? (or-of vs)) (boolean (some true? vs)))
         (nil? (or-of [])))))

(law not-of-is-an-involution
  (forall [b Bool]
    (= b (not-of (not-of b)))))

(law if-of-chooses-on-truth
  (forall [b Bool x Int y Int]
    (and (= (if-of b x y) (if b x y))
         (= (if-of b x x) x))))

;; --- equality ---------------------------------------------------------------

(law eq-is-reflexive-and-symmetric
  (forall [a Any b Any]
    (and (eq a a)
         (= (eq a b) (eq b a)))))

;; Reflexivity alone leaves (constantly true) standing, so one law has to say
;; when eq is false as well as when it is true.
(law eq-holds-exactly-when-the-values-are-equal
  (forall [a Int b Int]
    (= (eq a b) (= a b))))

(law not-of-is-the-negation-of-truth
  (forall [b Bool]
    (= (not-of b) (not b))))

;; --- the operators compose ---------------------------------------------------
;; The shapes the planner actually emits: a typed column's check is
;; [and [string? nm] [<= [count nm] max]], so congruence under composition is
;; what carries the per-operator laws to a whole constraint.

(law a-conjunction-rejects-what-a-conjunct-rejects
  (forall [a Int b Int]
    (if (lt b a)
      (not (and-of [(lt a b) (lte a b)]))
      true)))

(law a-disjunction-accepts-what-a-disjunct-accepts
  (forall [a Int b Int]
    (if (lt a b)
      (and-of [(or-of [(lt a b) (lte a b)])])
      true)))
