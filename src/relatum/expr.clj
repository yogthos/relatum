(ns relatum.expr
  "The value-level semantics of the expression operators, deliberately pure and
  throw-free.

  The engine turns an expression like [:and [integer? :a] [<= [count :a] 4]]
  into a function of a row; that construction has to raise (a malformed
  expression is a caller error) and cannot be law-checked. What *can* be
  checked is the meaning of each operator once its arguments are values, and
  that is what lives here, so both the engine and the planner's emitted
  constraints draw on one definition instead of two.")

(defn truthy?
  "Clojure truth: everything but nil and false."
  [v]
  (not (or (nil? v) (false? v))))

;; --- ordered comparisons ---------------------------------------------------
;; compare-based rather than numeric: a constraint may compare dates, strings,
;; keywords or numbers, and relic's own < / <= / > / >= are compare-based for
;; that reason.
;;
;; Two arguments each, the meaning of one comparison. Chaining arguments
;; belongs to the engine's < / <= wrappers, which are effects-edge code: a
;; chain is a fold over an unbounded argument list, and a law about it would be
;; a law about arities rather than about meaning.

(defn lt
  [x y]
  (neg? (compare x y)))

(defn lte
  [x y]
  (not (pos? (compare x y))))

(defn gt
  [x y]
  (pos? (compare x y)))

(defn gte
  [x y]
  (not (neg? (compare x y))))

(defn eq
  "Equality over values, including nil and collections."
  [a b]
  (= a b))

;; --- boolean composition --------------------------------------------------

(defn and-of
  "Every value truthy. Stops at the first falsey one, so a later value is never
  forced; an empty collection is vacuously true, as (every-pred) is.

  Written with drop-while, not (every? truthy? values) or a reduce: on jolt
  both of those walk the whole sequence past the first falsey value, so they
  would force values Clojure's every-pred leaves alone. The seq is what
  distinguishes 'no falsey value at all' from 'a falsey nil'."
  [values]
  (nil? (seq (drop-while truthy? values))))

(defn or-of
  "The first truthy value, or nil when there is none. Returns the value rather
  than true so an :or in value position keeps its result, as (some-fn) does.
  filter/first is lazy on jolt, where (some identity values) is not."
  [values]
  (first (filter truthy? values)))

(defn not-of
  [value]
  (not (truthy? value)))

(defn if-of
  "The then-branch when the test is truthy, else the else-branch. Two in one:
  an omitted else-branch is nil, which is (if test then)."
  [test then else]
  (if (truthy? test) then else))
