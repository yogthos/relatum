(ns relatum.graph-test
  "Tests for the CSR graph specification."
  (:require [clojure.test :refer [deftest is testing]]
            [relatum.engine :as rel]
            [relatum.graph :as g]))

;; ── construction and accessors ─────────────────────────────────────────

(deftest build-simple-graph
  (let [edges [{:src 0 :dst 1}
               {:src 1 :dst 2}
               {:src 2 :dst 3}
               {:src 0 :dst 2}]
        graph (g/graph edges)]
    (is (= 4 (g/n-nodes graph)))
    (is (= 4 (g/n-edges graph)))
    (is (= 2 (g/degree graph 0)))
    (is (= 1 (g/degree graph 1)))
    (is (= 1 (g/degree graph 2)))
    (is (= 0 (g/degree graph 3)))
    (is (= 0 (g/in-degree graph 0)))
    (is (= 1 (g/in-degree graph 1)))
    (is (= 2 (g/in-degree graph 2)))
    (is (= 1 (g/in-degree graph 3)))))

(deftest build-graph-with-weights
  (let [graph (g/graph [{:src 0 :dst 1 :weight 2.5}
                        {:src 1 :dst 0 :weight 3.5}])
        nbrs (g/neighbors graph 0)]
    (is (= 2 (g/n-nodes graph)))
    (is (some? (.weights graph)))
    (is (= 1 (count nbrs)))
    (is (= [1 2.5] (first nbrs)))))

(deftest neighbors-no-weights
  (let [graph (g/graph [{:src 0 :dst 1} {:src 0 :dst 2}])
        nbrs (g/neighbors graph 0)]
    (is (= 2 (count nbrs)))
    (is (= [1 1.0] (first nbrs)))))

(deftest graph-weights-align-with-targets
  (let [gr (g/graph [{:src 0 :dst 2 :weight 20.0}
                     {:src 0 :dst 1 :weight 10.0}
                     {:src 0 :dst 3 :weight 30.0}])
        nbrs (into {} (g/neighbors gr 0))]
    (is (= 10.0 (nbrs 1)))
    (is (= 20.0 (nbrs 2)))
    (is (= 30.0 (nbrs 3)))))

;; ── dictionary encoding ────────────────────────────────────────────────

(deftest interning-assigns-dense-first-seen-ids
  (let [graph (g/graph [{:src :a :dst :b} {:src :b :dst :c}])]
    (is (= [:a :b :c] (g/nodes graph)))
    (is (= 1 (g/degree graph :a)))
    (is (= 1 (g/in-degree graph :c)))))

(deftest interning-is-deterministic
  (let [edges (for [i (range 50)] {:src i :dst (rem (inc i) 50)})]
    (is (= (g/nodes (g/graph edges))
           (g/nodes (g/graph (reverse edges))))
        "each graph interns in first-seen order of its own input")))

(deftest interning-decodes-results
  (let [graph (g/graph [{:src :a :dst :b} {:src :b :dst :c}])
        d (into {} (map (juxt :node :distance)) (g/bfs graph :a))]
    (is (= 0 (d :a)))
    (is (= 1 (d :b)))
    (is (= 2 (d :c)))))

;; ── db integration ─────────────────────────────────────────────────────

(deftest graph-of-builds-from-a-db-table
  (let [db (rel/transact {} [:insert :Follows {:src :alice :dst :bob}]
                         [:insert :Follows {:src :bob :dst :carol}])
        graph (g/graph-of db :Follows)]
    (is (= 3 (g/n-nodes graph)))
    (is (= #{[:bob 1.0]} (set (g/neighbors graph :alice))))))

(deftest graph-of-honors-column-options
  (let [db (rel/transact {} [:insert :Link {:from :a :to :b :w 7.0}])
        graph (g/graph-of db [[:from :Link]] {:src :from :dst :to :weight :w})]
    (is (= #{[:b 7.0]} (set (g/neighbors graph :a))))))

;; ── BFS / DFS ──────────────────────────────────────────────────────────

(deftest bfs-distances
  (let [gr (g/graph [{:src 0 :dst 1} {:src 1 :dst 2} {:src 2 :dst 3} {:src 0 :dst 2}])
        d (into {} (map (juxt :node :distance)) (g/bfs gr 0))]
    (is (= 4 (count d)))
    (is (= 0 (d 0)))
    (is (= 1 (d 1)))
    (is (= 1 (d 2)))
    (is (= 2 (d 3)))))

(deftest bfs-unreachable-is-nil
  (let [gr (g/graph [{:src 0 :dst 1}])
        d (into {} (map (juxt :node :distance)) (g/bfs gr 0))]
    (is (= 2 (count d)) "n-nodes defaults to max node id + 1")
    (is (= 0 (d 0)))
    (is (= 1 (d 1)))
    (is (nil? (d 2)) "node 2 is beyond max id, not in the graph")))

(deftest bfs-unknown-start-yields-all-nil
  (let [gr (g/graph [{:src 0 :dst 1}])]
    (is (every? #(nil? (:distance %)) (g/bfs gr :nope)))))

(deftest dfs-visits-all-reachable
  (let [gr (g/graph [{:src 0 :dst 1} {:src 1 :dst 2} {:src 2 :dst 3} {:src 0 :dst 2}])
        o (into {} (map (juxt :node :order)) (g/dfs gr 0))]
    (is (= 4 (count o)))
    (is (= #{0 1 2 3} (set (vals o))))
    (is (= 0 (o 0)))))

;; ── Dijkstra ───────────────────────────────────────────────────────────

(deftest dijkstra-shortest-paths
  (let [gr (g/graph [{:src 0 :dst 1 :weight 1.0}
                     {:src 1 :dst 2 :weight 1.0}
                     {:src 0 :dst 2 :weight 5.0}])
        d (into {} (map (juxt :node :distance)) (g/dijkstra gr 0))]
    (is (= 0.0 (d 0)))
    (is (= 1.0 (d 1)))
    (is (= 2.0 (d 2)))))

(deftest dijkstra-unreachable-is-nil
  (let [gr (g/graph [{:src 0 :dst 1 :weight 1.0}])
        d (into {} (map (juxt :node :distance)) (g/dijkstra gr 0))]
    (is (nil? (d 2)))))

;; ── shortest-path ──────────────────────────────────────────────────────

(deftest shortest-path-unweighted-follows-bfs
  (let [gr (g/graph [{:src 0 :dst 1}
                     {:src 1 :dst 2}
                     {:src 0 :dst 2}])
        {:keys [path distance]} (g/shortest-path gr 0 2)]
    (is (= [0 2] path) "the direct edge is fewest hops")
    (is (= 1.0 distance))))

(deftest shortest-path-weighted-prefers-cheap-route
  (let [gr (g/graph [{:src 0 :dst 1 :weight 1.0}
                     {:src 1 :dst 2 :weight 1.0}
                     {:src 0 :dst 2 :weight 5.0}])
        {:keys [path distance]} (g/shortest-path gr 0 2)]
    (is (= [0 1 2] path))
    (is (= 2.0 distance))))

(deftest shortest-path-unreachable-is-nil
  (let [gr (g/graph [{:src 0 :dst 1} {:src 2 :dst 3}])]
    (is (nil? (g/shortest-path gr 0 3)))))

(deftest shortest-path-to-self
  (let [gr (g/graph [{:src 0 :dst 1}])]
    (is (= {:path [0] :distance 0.0} (g/shortest-path gr 0 0)))))

;; ── PageRank ───────────────────────────────────────────────────────────

(deftest pagerank-converges
  (let [gr (g/graph [{:src 0 :dst 1} {:src 1 :dst 2} {:src 2 :dst 0}
                     {:src 0 :dst 2} {:src 1 :dst 0}])
        r (g/pagerank gr :iters 30)
        total (transduce (map :rank) + 0.0 r)]
    (is (= 3 (count r)))
    (is (< 0.9 total 1.1) (str "Rank sum = " total))))

(deftest pagerank-single-node
  (let [gr (g/graph [{:src 0 :dst 0}])
        r (g/pagerank gr :iters 10)]
    (is (= 1 (count r)))
    (is (= 1.0 (:rank (first r))))))

(deftest page-rank-positional-matches-kwargs
  (let [gr (g/graph [{:src 0 :dst 1} {:src 1 :dst 2} {:src 2 :dst 0}
                     {:src 0 :dst 2} {:src 1 :dst 0}])
        r1 (g/page-rank gr 30 0.85)
        r2 (g/pagerank gr :iters 30 :damping 0.85)]
    (is (= (mapv :rank r1) (mapv :rank r2)))))

;; ── connected components ───────────────────────────────────────────────

(deftest connected-components-two-groups
  (let [gr (g/graph [{:src 0 :dst 1} {:src 1 :dst 2} {:src 3 :dst 4}])
        cc (into {} (map (juxt :node :component)) (g/connected-components gr))]
    (is (= 5 (count cc)))
    (is (= (cc 0) (cc 1) (cc 2)))
    (is (= (cc 3) (cc 4)))
    (is (not= (cc 0) (cc 3)))))

(deftest connected-components-single-edge
  (let [gr (g/graph [{:src 0 :dst 1}])
        cc (into {} (map (juxt :node :component)) (g/connected-components gr))]
    (is (= (cc 0) (cc 1)))))

(deftest connected-components-fully-connected
  (let [gr (g/graph [{:src 0 :dst 1} {:src 0 :dst 2} {:src 1 :dst 0}
                     {:src 1 :dst 2} {:src 2 :dst 0} {:src 2 :dst 1}])
        cc (into {} (map (juxt :node :component)) (g/connected-components gr))
        comp (cc 0)]
    (is (every? #(= comp %) (vals cc)))))

(deftest component-representative-is-a-decoded-node
  (let [gr (g/graph [{:src :x :dst :y} {:src :y :dst :z} {:src :p :dst :q}])
        cc (into {} (map (juxt :node :component)) (g/connected-components gr))]
    (is (contains? #{:x :y :z} (cc :x)))
    (is (contains? #{:p :q} (cc :p)))))

;; ── larger graph / degenerate cases ────────────────────────────────────

(deftest build-large-graph
  (let [n 10000
        edges (map (fn [i] {:src (rem i 100) :dst (rem (inc i) 100)}) (range n))
        graph (g/graph edges)]
    (is (= 100 (g/n-nodes graph)))
    (is (= n (g/n-edges graph)))))

(deftest empty-graph
  (let [gr (g/graph [])]
    (is (= 0 (g/n-nodes gr)))
    (is (= 0 (g/n-edges gr)))
    (is (= [] (g/nodes gr)))
    (is (empty? (g/bfs gr 0)))
    (is (empty? (g/connected-components gr)))
    (is (empty? (g/pagerank gr)))))

(deftest self-loops-and-parallel-edges
  (let [gr (g/graph [{:src 0 :dst 0} {:src 0 :dst 1} {:src 0 :dst 1}])]
    (is (= 2 (g/n-nodes gr)))
    (is (= 3 (g/n-edges gr)))
    (is (= 3 (g/degree gr 0)))
    (is (= 2 (g/in-degree gr 1)))))
