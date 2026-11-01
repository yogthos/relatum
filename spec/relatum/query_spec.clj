(ns relatum.query-spec
  "Laws for the query surface an agent actually drives: bytes in as a command,
  rows out as answers. The engine underneath is relic's dataflow machine, which
  this spec deliberately does not reach into; what it pins is the meaning a
  caller can rely on — an insert shows up, a repeated insert does not duplicate,
  a delete removes, and a filter keeps exactly the rows that match."
  (:require [writ.spec :refer [spec ann law graph]]
            [relatum.query]))

(spec relatum.query)

;; --- signatures -----------------------------------------------------------

(ann rows [Any Any -> (Opt (List (Map Keyword Any)))])
(ann insert [Any Any -> Any])

;; --- the graph ------------------------------------------------------------

;; One story, so one graph: a database is a value, `insert` takes a command and
;; gives the next database, `rows` takes a query and gives answers.
(graph query
  {:start [:db 1]
   :states {:db Any
            :rows (Opt (List (Map Keyword Any)))}
   :final [:rows]
   :edges {:db {[insert Any] #{:db}
                [rows Any] #{:rows}}}})

;; --- laws -----------------------------------------------------------------

;; Stating only the empty case would be true of a constant answer too, so this
;; law names both directions: nothing before a write, exactly the write after.
(law a-fresh-database-has-no-rows
  (forall [a Int]
    (let [db (insert {} [:insert :qt {:id a}])]
      (and (empty? (rows {} [[:from :qt]]))
           (= [{:id a}] (vec (rows db [[:from :qt]])))))))

(law an-inserted-row-is-the-only-row
  (forall [a Int]
    (= (set (rows (insert {} [:insert :qt {:id a}]) [[:from :qt]]))
       #{{:id a}})))

(law inserting-the-same-row-twice-keeps-one-copy
  (forall [a Int]
    (let [row {:id a}
          db (insert (insert {} [:insert :qt row]) [:insert :qt row])]
      (= 1 (count (rows db [[:from :qt]]))))))

(law a-where-clause-keeps-exactly-the-matching-rows
  (forall [a Int]
    (let [db (insert {} [:insert :qt {:id a}])]
      (and (= 1 (count (rows db [[:from :qt] [:where [= :id a]]])))
           (= 0 (count (rows db [[:from :qt] [:where [< :id a]]])))))))

(law a-delete-exact-removes-the-row-it-names
  (forall [a Int]
    (let [row {:id a}
          db (insert (insert {} [:insert :qt row]) [:delete-exact :qt row])]
      (empty? (rows db [[:from :qt]])))))

(law a-table-you-never-wrote-to-stays-empty
  (forall [a Int]
    (empty? (rows (insert {} [:insert :qt {:id a}]) [[:from :qother]]))))

(law a-query-that-asks-for-nothing-keeps-nothing
  (forall [a Int]
    (empty? (rows (insert {} [:insert :qt {:id a}])
                  [[:from :qt] [:where [= :id (inc a)]]]))))

;; --- relational identities ------------------------------------------------
;;
;; These are the algebraic laws a relational engine is supposed to satisfy:
;; filtering is order-independent and idempotent, union commutes, and the empty
;; relation is the identity for union and difference. Stating them over `rows`
;; rather than over the dataflow graph is what makes them checkable at all —
;; the graph is effectful, the surface is not.
;;
;; `:qempty` is a table no law ever writes to, so a query against it is a
;; relation known to be empty without needing to reason about generated values.

(law filtering-is-order-independent
  (forall [a Int b Int]
    (let [db (insert (insert {} [:insert :qt {:id a}]) [:insert :qt {:id b}])]
      (= (set (rows db [[:from :qt] [:where [> :id a]] [:where [< :id b]]]))
         (set (rows db [[:from :qt] [:where [< :id b]] [:where [> :id a]]]))))))

(law filtering-twice-changes-nothing
  (forall [a Int]
    (let [db (insert (insert {} [:insert :qt {:id a}]) [:insert :qt {:id (inc a)}])]
      (= (set (rows db [[:from :qt] [:where [> :id a]]]))
         (set (rows db [[:from :qt] [:where [> :id a]] [:where [> :id a]]]))))))

(law a-union-with-the-empty-relation-is-the-identity
  (forall [a Int]
    (let [db (insert {} [:insert :qt {:id a}])]
      (= (set (rows db [[:from :qt]]))
         (set (rows db [[:from :qt] [:union [[:from :qempty]]]]))))))

(law a-union-is-commutative
  (forall [a Int b Int]
    (let [db (insert (insert {} [:insert :qt {:id a}]) [:insert :qt {:id b}])]
      (= (set (rows db [[:from :qt] [:where [= :id a]]
                        [:union [[:from :qt] [:where [= :id b]]]]]))
         (set (rows db [[:from :qt] [:where [= :id b]]
                        [:union [[:from :qt] [:where [= :id a]]]]]))))))

(law intersecting-a-relation-with-itself-changes-nothing
  (forall [a Int]
    (let [db (insert {} [:insert :qt {:id a}])]
      (= (set (rows db [[:from :qt]]))
         (set (rows db [[:from :qt] [:intersection [[:from :qt]]]]))))))

(law subtracting-the-empty-relation-changes-nothing
  (forall [a Int]
    (let [db (insert {} [:insert :qt {:id a}])]
      (= (set (rows db [[:from :qt]]))
         (set (rows db [[:from :qt] [:difference [[:from :qempty]]]]))))))

(law a-selection-keeps-exactly-the-columns-it-names
  (forall [a Int]
    (let [db (insert {} [:insert :qt {:id a :name "n"}])]
      (= (set (rows db [[:from :qt] [:select :id]])) #{{:id a}}))))
