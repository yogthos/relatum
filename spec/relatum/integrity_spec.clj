(ns relatum.integrity-spec
  "Laws for foreign-key decisions, stated on rows rather than on databases.

  A clause is {child-column parent-column}. The properties here are the ones an
  insert depends on: a row has a parent exactly when some parent agrees with it
  on every column of the clause, and a batch of rows is accepted exactly when
  none of them is left dangling."
  (:require [writ.spec :refer [spec ann law graph]]
            [relatum.integrity]))

(spec relatum.integrity)

;; --- signatures -----------------------------------------------------------
;; The clause, a row and the candidate parents are all plain maps and vectors
;; whose exact shape varies with the declaration, so they are typed Any and the
;; laws say what the columns mean.

(ann same-key? [Any Any Any -> Bool])
(ann has-parent? [Any Any Any -> Bool])
(ann fk-verdict [Any Any Any -> (Map Keyword Any)])
(ann check [Any Any Any -> (Vec Any)])
(ann check-verdict [Any Any Any -> (Map Keyword Any)])

(graph referencing
  {:start [:clause {:c :id}]
   :states {:clause (Map Keyword Keyword)
            :verdict (Map Keyword Any)
            :rows (Vec Any)
            :flag Bool}
   :final [:flag :verdict :rows]
   :edges {:clause {[same-key? Any Any] #{:flag}
                    [has-parent? Any Any] #{:flag}
                    [fk-verdict Any Any] #{:verdict}
                    [check Any Any] #{:rows}
                    [check-verdict Any Any] #{:verdict}}}})

;; --- matching -------------------------------------------------------------

(law a-clause-matches-exactly-when-the-two-keys-are-equal
  (forall [a Int b Int]
    (= (same-key? {:c :id} {:c a} {:id b}) (= a b))))

(law a-row-has-a-parent-exactly-when-one-of-them-agrees-with-it
  (forall [a Int b Int]
    (= (has-parent? {:c :id} {:c a} [{:id b}]) (= a b))))

(law a-compound-clause-needs-every-column-it-names
  (forall [a Int b Int]
    (= (same-key? {:c :id :d :id2} {:c a :d b} {:id a :id2 (inc b)}) false)))

(law a-mismatch-in-an-earlier-column-settles-the-answer
  (forall [a Int b Int]
    (= (same-key? {:c :id :d :id2 :e :id3}
                  {:c a :d b :e 0}
                  {:id (inc a) :id2 b :id3 0})
       false)))

(law a-later-parent-still-satisfies-the-reference
  (forall [a Int b Int]
    (or (= a b)
        (true? (has-parent? {:c :id} {:c a} [{:id b} {:id a}])))))

(law a-reference-with-no-candidate-parents-is-unsatisfied
  (forall [a Int]
    (false? (has-parent? {:c :id} {:c a} []))))

;; --- verdicts -------------------------------------------------------------

(law a-satisfied-reference-passes-the-row-through
  (forall [a Int]
    (= {:ok true :value {:c a}}
       (fk-verdict {:c :id} {:c a} [{:id a}]))))

(law a-dangling-reference-is-rejected-and-carries-the-row
  (forall [a Int b Int]
    (let [v (fk-verdict {:c :id} {:c a} [{:id b}])]
      (if (= a b)
        (true? (:ok v))
        (and (false? (:ok v))
             (= {:c a} (:row v))
             (= {:c :id} (:clause v)))))))

(law check-finds-exactly-the-rows-without-a-parent
  (forall [a Int b Int]
    (= (check {:c :id} [{:c a} {:c b}] [{:id a}])
       (if (= a b) [] [{:c b}]))))

(law a-checked-batch-passes-exactly-when-nothing-was-found
  (forall [a Int b Int]
    (= (:ok (check-verdict {:c :id} [{:c a} {:c b}] [{:id a}]))
       (empty? (check {:c :id} [{:c a} {:c b}] [{:id a}])))))
