(ns relatum.type-spec
  "Laws for the type vocabulary. These quantify over the values an agent may
  actually pass, so a regression in parse-type or in a type predicate shows up
  as a failed law rather than as a table that quietly accepts junk."
  (:require [writ.spec :refer [spec ann law graph]]))

(spec relatum.type)

(ann parse-type [Any -> (Opt Any)])
(ann conforms? [Any Any -> Bool])
(ann validate [Any Any -> Any])
(ann varchar-length [String -> (Opt Nat)])
(ann varchar-conforms? [Nat String -> Bool])
(ann known-types [-> (List String)])

;; --- the vocabulary is the one the parser understands ---------------------
(law every-known-type-parses-and-keeps-its-place
  (forall [i Nat]
    (let [names (known-types)
          n (count names)
          nm (nth names (mod i n))]
      (and (some? (parse-type nm))
           (= nm (nth (sort names) (mod i n)))
           (= 9 n)))))

;; --- the type predicate agrees with the language -------------------------

(law every-int-conforms-to-int
  (forall [v Int] (conforms? (parse-type :int) v)))

(law every-float-misses-int
  (forall [x Float] (not (conforms? (parse-type :int) x))))

(law every-string-is-text
  (forall [s String] (conforms? (parse-type :text) s)))

;; --- parse-type reads every spelling it offers ---------------------------
(law parse-type-reads-varchar
  (forall [n Nat]
    (let [declared (str "varchar(" n ")")
          parsed (parse-type declared)]
      (and (= declared (:sql parsed))
           (= n (:max-length parsed))))))

(law parse-type-reads-a-string
  (forall [s String]
    (conforms? (parse-type "text") s)))

;; --- validate covers nil types and the coercing kinds --------------------
(law validate-without-a-type-accepts
  (forall [v (Opt Any)] (:ok (validate nil v))))

(law validate-rejects-varchar-that-is-too-long
  (forall [s String]
    (not (:ok (validate (parse-type "varchar(0)") (str s "!"))))))

;; --- a declaration is read into a descriptor, or not at all ---------------
;; The step of the one state change this vocabulary has: DDL text on the way in,
;; a type descriptor (or nil, when the text is not a type) on the way out.

(graph reading
  {:start [:text "varchar(3)"]
   :states {:text String, :descriptor (Opt Any)}
   :final [:descriptor]
   :edges {:text {[parse-type] #{:descriptor}}}})

(law every-int-conforms-to-real
  (forall [n Int] (conforms? (parse-type :real) n)))

(law every-float-conforms-to-real
  (forall [x Float] (conforms? (parse-type :real) x)))

(law every-bool-conforms-to-bool
  (forall [b Bool] (conforms? (parse-type :bool) b)))

(law every-keyword-is-keyword
  (forall [k Keyword] (conforms? (parse-type :keyword) k)))

(law validate-accepts-real
  (forall [n Int] (:ok (validate (parse-type :real) n))))

;; --- varchar bounds ------------------------------------------------------

(law varchar-length-reads-the-declaration
  (forall [n Nat] (= n (varchar-length (str "varchar(" n ")")))))

(law a-string-fits-exactly-its-own-length
  (forall [s String] (varchar-conforms? (count s) s)))

(law one-character-too-many-does-not-fit
  (forall [s String] (not (varchar-conforms? (count s) (str s "!")))))

;; --- validate is total and agrees with conforms? -------------------------

(law validate-accepts-what-conforms
  (forall [n Int] (:ok (validate (parse-type :int) n))))

(law validate-rejects-a-non-string-in-a-varchar
  (forall [n Int] (not (:ok (validate (parse-type "varchar(3)") n)))))

(law validate-rejects-a-string-in-an-int
  (forall [s String] (not (:ok (validate (parse-type :int) s)))))

(law validate-rejects-what-does-not
  (forall [x Float] (false? (:ok (validate (parse-type :int) x)))))

(law a-rejected-value-says-why
  (forall [x Float] (contains? (validate (parse-type :int) x) :error)))
