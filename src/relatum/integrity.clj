(ns relatum.integrity
  "Foreign-key decisions as pure functions over plain rows.

  A clause is {child-column parent-column} - the direction the engine's :fk op
  uses. The rows are ordinary maps, so nothing here needs a database, a
  registry or a graph: given a clause, a row and the rows it might point at,
  the answer is a value.

  A reference is satisfied only by a parent that agrees on every column of the
  clause - a null child column is not a wildcard here, which matches the
  engine's :fk op (relic rejects a row whose reference resolves to nothing).

  Verdicts follow relatum.plan: {:ok true :value ...} or
  {:ok false :error <keyword> ...}. The effects - raising, applying a cascade
  delete, mutating a database - stay in relatum.engine.dataflow and
  relatum.core, which is what lets writ check this namespace.")

;; ---------------------------------------------------------------------------
;; keys

(defn- child-columns [clause]
  (vec (keys clause)))

(defn- parent-column [clause child-column]
  (get clause child-column))

(defn same-key?
  "Whether child-row and parent-row agree on every column in the clause."
  [clause child-row parent-row]
  (reduce (fn [acc child-column]
            (if acc
              (= (get child-row child-column)
                 (get parent-row (parent-column clause child-column)))
              acc))
          true
          (child-columns clause)))

;; ---------------------------------------------------------------------------
;; references

(defn has-parent?
  "Whether at least one of parents agrees with row on the clause's columns."
  [clause row parents]
  (reduce (fn [acc parent-row]
            (if acc acc (same-key? clause row parent-row)))
          false
          parents))

(defn fk-verdict
  "Whether row satisfies its reference against parents. A rejected verdict
  carries the row and the clause, so the caller can report both."
  [clause row parents]
  (if (has-parent? clause row parents)
    {:ok true :value row}
    {:ok false :error :foreign-key-violation :row row :clause clause}))

(defn check
  "Every row of rows that does not satisfy the reference, in order."
  [clause rows parents]
  (reduce (fn [acc row]
            (if (has-parent? clause row parents) acc (conj acc row)))
          []
          rows))

(defn check-verdict
  "A rejected verdict naming the first unsatisfied row, or a verdict carrying
  the rows that were checked."
  [clause rows parents]
  (let [bad (check clause rows parents)]
    (if (seq bad)
      {:ok false :error :foreign-key-violation :row (first bad) :rows bad :clause clause}
      {:ok true :value (vec rows)})))

;; ---------------------------------------------------------------------------
;; cascades

(defn dependents
  "The children that hold a reference to parent-row."
  [clause parent-row children]
  (reduce (fn [acc child-row]
            (if (same-key? clause child-row parent-row)
              (conj acc child-row)
              acc))
          []
          children))

(defn delete-verdict
  "What removing parent-row means for its children.

  With {:cascade :delete} the verdict carries exactly the children that point
  at it, which is what the delete should also remove. Without it, a parent
  that still has dependents is refused: rows the delete cannot touch must not
  be silently orphaned."
  [clause parent-row children {:keys [cascade]}]
  (let [held (dependents clause parent-row children)]
    (cond
      (empty? held) {:ok true :value []}
      (= :delete cascade) {:ok true :value held}
      :else {:ok false :error :foreign-key-violation
             :row parent-row :rows held :clause clause})))
