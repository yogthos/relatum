(ns relatum.graph
  "CSR graph algorithms over an in-memory relatum database, which follows CSR layout.

  The engine stores row maps, not typed columns, so the boundary differs from
  flatiron: edges are maps {:src s :dst d :weight w} (any seq), and node
  values that are not integers are dictionary-encoded to dense long ids in
  first-seen order. Integer node values pass through as ids directly and the
  node count defaults to max id + 1, matching flatiron.

  Results come back as seqs of row maps {:node value ...}, so they can be
  re-transacted into the database."
  (:require [relatum.engine :as rel]))

(defrecord Graph [^longs fwd-offsets ^longs fwd-targets
                  ^longs rev-offsets ^longs rev-targets
                  ^long n-nodes ^long n-edges ^doubles weights
                  node->id ids])

;; ── Build helpers ─────────────────────────────────────────────────────
(defn- check-edge
  "An edge must name both endpoints, integer endpoints are node ids and ids
   are nonnegative, and a weight must be a finite nonnegative number —
   Dijkstra does not terminate on negative cycles."
  [e ^long i]
  (let [throw! (fn [msg data]
                 (throw (ex-info (str "edge " i ": " msg)
                                 (assoc data :relatum/error (:relatum/error data)
                                        :edge-index i :edge e))))]
    (when (or (nil? (:src e)) (nil? (:dst e)))
      (throw! "needs non-nil :src and :dst" {:relatum/error :missing-endpoint}))
    (when (and (integer? (:src e)) (neg? (:src e)))
      (throw! "integer node values are ids and must be nonnegative"
              {:relatum/error :negative-node :which :src}))
    (when (and (integer? (:dst e)) (neg? (:dst e)))
      (throw! "integer node values are ids and must be nonnegative"
              {:relatum/error :negative-node :which :dst}))
    (when-some [w (:weight e)]
      (when (or (not (number? w))
                (let [d (double w)]
                  (or (Double/isInfinite d) (Double/isNaN d) (neg? d))))
        (throw! ":weight must be a finite nonnegative number"
                {:relatum/error :bad-weight :weight w})))))

(defn- build-csr-w
  "Build a CSR keyed by `key-arr` (one entry per edge). `tgt-arr` holds the
   adjacency target per edge, and `wt-arr` (optional) the matching weights.
   Targets and weights are scattered together so they stay aligned.
   Returns [offsets targets weights-or-nil]."
  [^longs key-arr ^longs tgt-arr wt-arr n-edges n-nodes]
  (let [n-edges (long n-edges)
        n-nodes (long n-nodes)
        deg (long-array n-nodes)]
    (dotimes [i n-edges]
      (let [k (aget key-arr i)]
        (aset deg k (unchecked-inc (aget deg k)))))
    (let [off (long-array (unchecked-inc n-nodes))
          pos (long-array n-nodes)
          tgt (long-array n-edges)
          ow  (when wt-arr (double-array n-edges))]
      (loop [i 0 cum 0]
        (when (< i n-nodes)
          (aset off i cum) (aset pos i cum)
          (recur (unchecked-inc i) (unchecked-add cum (aget deg i)))))
      (aset off n-nodes n-edges)
      (dotimes [i n-edges]
        (let [k (aget key-arr i)
              p (aget pos k)]
          (aset tgt p (aget tgt-arr i))
          (when wt-arr (aset ^doubles ow p (aget wt-arr i)))
          (aset pos k (unchecked-inc p))))
      [off tgt ow])))

;; ── graph ─────────────────────────────────────────────────────────────
(defn graph
  "Build a graph from a seq of edge maps {:src s :dst d :weight w?}. An
   optional :weight per edge makes it weighted (edges without one default
   to 1.0). Non-integer node values are interned to dense ids in first-seen
   order; integer node values are used as ids and n-nodes is max id + 1."
  [edges]
  (let [edges (vec edges)
        n-edges (count edges)
        _ (dotimes [i n-edges] (check-edge (nth edges i) i))
        integer-node? integer?
        numeric? (every? (fn [e] (and (integer-node? (:src e))
                                      (integer-node? (:dst e))))
                         edges)]
    (if numeric?
      ;; flatiron semantics: node values are ids, n-nodes = max id + 1
      (let [src (long-array n-edges) dst (long-array n-edges)]
        (dotimes [i n-edges]
          (aset src i (long (:src (nth edges i))))
          (aset dst i (long (:dst (nth edges i)))))
        (let [nn (if (zero? n-edges)
                   0
                   (let [mx (volatile! 0)]
                     (dotimes [i n-edges] (vswap! mx max (aget src i) (aget dst i)))
                     (unchecked-inc @mx)))
              wts (when (some #(some? (:weight %)) edges)
                    (let [out (double-array n-edges)]
                      (dotimes [i n-edges]
                        (aset out i (or (:weight (nth edges i)) 1.0)))
                      out))
              [fo ft fw] (build-csr-w src dst wts n-edges nn)
              [ro rt _]  (build-csr-w dst src nil n-edges nn)]
          (->Graph fo ft ro rt nn n-edges fw nil nil)))
      ;; dictionary encoding: intern node values to dense ids
      (let [intern (fn [m ids v]
                     (if-some [i (get m v)]
                       [m ids i]
                       [(assoc m v (count ids)) (conj ids v) (count ids)]))
            [node->id ids src dst]
            (loop [i 0 m {} ids [] src (long-array n-edges) dst (long-array n-edges)]
              (if (= i n-edges)
                [m ids src dst]
                (let [e (nth edges i)
                      [m ids si] (intern m ids (:src e))
                      [m ids di] (intern m ids (:dst e))]
                  (aset src i si) (aset dst i di)
                  (recur (unchecked-inc i) m ids src dst))))
            nn (count ids)
            wts (when (some #(some? (:weight %)) edges)
                  (let [out (double-array n-edges)]
                    (dotimes [i n-edges]
                      (aset out i (or (:weight (nth edges i)) 1.0)))
                    out))
            [fo ft fw] (build-csr-w src dst wts n-edges nn)
            [ro rt _]  (build-csr-w dst src nil n-edges nn)]
        (->Graph fo ft ro rt nn n-edges fw node->id ids)))))

;; ── node access ───────────────────────────────────────────────────────
(defn n-nodes [^Graph g] (.n-nodes g))
(defn n-edges [^Graph g] (.n-edges g))

(defn nodes
  "Every node value in interned order (or [0..n) when ids are integers)."
  [^Graph g]
  (if-some [ids (.ids g)]
    (vec ids)
    (vec (range (.n-nodes g)))))

(defn- node-id
  "Resolve a node value (or an integer id) to its internal id, or nil when
   the node is not in the graph. On interned graphs a lookup by value wins;
   an integer that is not a value falls back to its internal id."
  [^Graph g n]
  (if-some [m (.node->id g)]
    (or (get m n)
        (when (and (integer? n) (<= 0 n) (< n (.n-nodes g)))
          (long n)))
    (when (and (integer? n) (<= 0 n) (< n (.n-nodes g)))
      (long n))))

(defn- decode-id [^Graph g ^long i]
  (if-some [ids (.ids g)]
    (nth ids i)
    i))

(defn degree
  "Out-degree of a node. Accepts the node value or its integer id."
  [^Graph g n]
  (when-some [i (node-id g n)]
    (let [^longs off (.fwd-offsets g)]
      (unchecked-subtract (aget off (unchecked-inc i)) (aget off i)))))

(defn in-degree
  "In-degree of a node. Accepts the node value or its integer id."
  [^Graph g n]
  (when-some [i (node-id g n)]
    (let [^longs off (.rev-offsets g)]
      (unchecked-subtract (aget off (unchecked-inc i)) (aget off i)))))

(defn neighbors
  "[target weight] pairs along out-edges of a node; targets are decoded to
   node values when the graph interned them."
  [^Graph g n]
  (when-some [i (node-id g n)]
    (let [^longs off (.fwd-offsets g) ^longs tgt (.fwd-targets g)
          ^doubles wts (.weights g)
          start (aget off i) end (aget off (unchecked-inc i))]
      (for [j (range start end)]
        [(decode-id g (aget tgt j)) (if wts (aget wts j) 1.0)]))))

;; ── result rows ───────────────────────────────────────────────────────
(defn- i64-rows
  "Rows of {:node value k v-or-nil} from a long-array of per-node values.
   Entries equal to `missing` become nil."
  [value-name ^longs vals ^long nn ^long missing g]
  (for [i (range nn)]
    {:node (decode-id g i)
     value-name (let [v (aget vals i)]
                  (when (not= v missing) v))}))

(defn- f64-rows
  [value-name ^doubles vals ^long nn g]
  (for [i (range nn)]
    {:node (decode-id g i)
     value-name (let [v (aget vals i)]
                  (when (not (Double/isInfinite v)) v))}))

;; ── Traversal ──────────────────────────────────────────────────────────
(defn bfs
  "Breadth-first search from `start` (node value or id). Returns a seq of
   {:node n :distance hops}; unreachable nodes have nil distance."
  [^Graph g start]
  (let [nn (.n-nodes g)
        ^longs fo (.fwd-offsets g)
        ^longs ft (.fwd-targets g)
        dist (long-array nn)]
    (java.util.Arrays/fill dist (long -1))
    (when-some [s (node-id g start)]
      (aset dist (long s) 0)
      (let [q (java.util.ArrayDeque.)]
        (.add q (Long/valueOf s))
        (loop []
          (when-not (.isEmpty q)
            (let [u  (long (.poll q))
                  du (aget dist u)]
              (loop [j (aget fo u)]
                (when (< j (aget fo (unchecked-inc u)))
                  (let [v (aget ft j)]
                    (when (== -1 (aget dist v))
                      (aset dist v (unchecked-inc du))
                      (.add q (Long/valueOf v))))
                  (recur (unchecked-inc j)))))
            (recur)))))
    (i64-rows :distance dist nn -1 g)))

(defn dfs
  "Iterative depth-first search from `start`. Returns a seq of {:node n
   :order i} giving the preorder visitation index of each reachable node;
   unvisited nodes are nil."
  [^Graph g start]
  (let [nn (.n-nodes g)
        ^longs fo (.fwd-offsets g)
        ^longs ft (.fwd-targets g)
        order   (long-array nn)
        visited (boolean-array nn)
        cnt     (long-array 1)]
    (java.util.Arrays/fill order (long -1))
    (when-some [s (node-id g start)]
      (let [stack (java.util.ArrayDeque.)]
        (.push stack (Long/valueOf s))
        (loop []
          (when-not (.isEmpty stack)
            (let [u (long (.pop stack))]
              (when-not (aget visited u)
                (aset visited u true)
                (aset order u (aget cnt 0))
                (aset cnt 0 (unchecked-inc (aget cnt 0)))
                ;; Push neighbors in reverse so the lowest-id neighbor is
                ;; visited first.
                (loop [j (unchecked-dec (aget fo (unchecked-inc u)))]
                  (when (>= j (aget fo u))
                    (let [v (aget ft j)]
                      (when-not (aget visited v)
                        (.push stack (Long/valueOf v))))
                    (recur (unchecked-dec j))))))
            (recur)))))
    (i64-rows :order order nn -1 g)))

;; ── Dijkstra ───────────────────────────────────────────────────────────
(defn- relax-edges
  "One flatiron relaxation pass over u's out-edges. dist is mutated; new pq
   entries are folded into `pq`; prev (when non-nil) records the predecessor
   of each improved node."
  [^longs fo ^longs ft wts ^doubles dist ^longs prev ^long u ^double d pq]
  (loop [j (aget fo u) pq pq]
    (if (< j (aget fo (unchecked-inc u)))
      (let [v  (aget ft j)
            w  (if wts (aget wts j) 1.0)
            nd (+ d w)]
        (recur (unchecked-inc j)
               (if (< nd (aget dist v))
                 (do (aset dist v nd)
                     (when prev (aset prev v u))
                     (update pq nd (fnil conj []) v))
                 pq)))
      pq)))

(defn- dijkstra-fill
  "Fill dist (and prev, when non-nil) from `s`. flatiron's dijkstra with the
   java.util.PriorityQueue replaced by a sorted-map (jolt has no PriorityQueue
   constructor): distance -> bucket of nodes. Each bucket is drained fully;
   stale entries are skipped by the dist check, as in flatiron."
  [^longs fo ^longs ft wts ^doubles dist ^longs prev ^long s]
  (aset dist s 0.0)
  (loop [pq (sorted-map 0.0 [s])]
    (when-some [[d us] (first pq)]
      (let [pq (loop [pq (dissoc pq d) us us]
                 (if-let [u (first us)]
                   (recur
                    (if (<= d (aget dist u))
                      (relax-edges fo ft wts dist prev u d pq)
                      pq)
                    (next us))
                   pq))]
        (recur pq)))))

(defn dijkstra
  "Single-source shortest paths from `start` over edge weights. Edges without
   weights count as weight 1.0. Returns a seq of {:node n :distance d};
   unreachable nodes have nil distance."
  [^Graph g start]
  (let [nn (.n-nodes g)
        dist (double-array nn)]
    (java.util.Arrays/fill dist Double/POSITIVE_INFINITY)
    (when-some [s (node-id g start)]
      (dijkstra-fill (.fwd-offsets g) (.fwd-targets g) (.weights g) dist nil (long s)))
    (f64-rows :distance dist nn g)))

;; ── shortest-path ──────────────────────────────────────────────────────
(defn- bfs-fill
  "Fill dist (hops) and prev from `s` via BFS with predecessor tracking.
   Same walk as bfs above."
  [^longs fo ^longs ft ^longs dist ^longs prev ^long s]
  (aset dist s 0)
  (let [q (java.util.ArrayDeque.)]
    (.add q (Long/valueOf s))
    (loop []
      (when-not (.isEmpty q)
        (let [u  (long (.poll q))
              du (aget dist u)]
          (loop [j (aget fo u)]
            (when (< j (aget fo (unchecked-inc u)))
              (let [v (aget ft j)]
                (when (== -1 (aget dist v))
                  (aset dist v (unchecked-inc du))
                  (aset prev v u)
                  (.add q (Long/valueOf v))))
              (recur (unchecked-inc j)))))
        (recur)))))

(defn- rebuild-path
  "Walk prev back from `to`, decoding ids into node values."
  [^Graph g ^longs prev ^long to]
  (loop [v to acc ()]
    (if (== v -1)
      (vec acc)
      (recur (aget prev v) (conj acc (decode-id g v))))))

(defn shortest-path
  "Fewest-hops (unweighted) or cheapest (weighted, when any edge carries a
   :weight) path from `a` to `b`, as {:path [nodes...] :distance d}. Hops
   are longs; weighted distance is a double. Returns nil when there is no
   path. `a` and `b` accept node values or ids."
  [^Graph g a b]
  (when-some [from (node-id g a)]
    (when-some [to (node-id g b)]
      (if (.weights g)
        (let [nn (.n-nodes g)
              dist (double-array nn)
              prev (long-array nn)]
          (java.util.Arrays/fill dist Double/POSITIVE_INFINITY)
          (java.util.Arrays/fill prev -1)
          (dijkstra-fill (.fwd-offsets g) (.fwd-targets g) (.weights g) dist prev from)
          (when (not= (aget dist to) Double/POSITIVE_INFINITY)
            {:path (rebuild-path g prev to)
             :distance (aget dist to)}))
        (let [nn (.n-nodes g)
              dist (long-array nn)
              prev (long-array nn)]
          (java.util.Arrays/fill dist (long -1))
          (java.util.Arrays/fill prev -1)
          (bfs-fill (.fwd-offsets g) (.fwd-targets g) dist prev from)
          (when (not= -1 (aget dist to))
            {:path (rebuild-path g prev to)
             :distance (double (aget dist to))}))))))

;; ── PageRank ───────────────────────────────────────────────────────────
(defn pagerank
  "flatiron's PageRank: damping and iters. Returns rows of
   {:node n :rank r} whose ranks sum to ~1.0."
  [^Graph g & {:keys [damping iters] :or {damping 0.85 iters 20}}]
  (let [nn (.n-nodes g)
        ^longs fo (.fwd-offsets g) ^longs ro (.rev-offsets g) ^longs rt (.rev-targets g)
        rank (double-array nn) rank2 (double-array nn)
        init (/ 1.0 (double nn))]
    (dotimes [i nn] (aset rank i init))
    (dotimes [_iter iters]
      (let [ds (loop [u 0 ds 0.0]
                 (if (< u nn)
                   (if (= (aget fo (unchecked-inc u)) (aget fo u))
                     (recur (unchecked-inc u) (+ ds (aget rank u)))
                     (recur (unchecked-inc u) ds))
                   ds))
            base (+ (/ (- 1.0 damping) (double nn)) (* damping (/ ds (double nn))))]
        (dotimes [v nn]
          (let [sum (loop [j (aget ro v) s 0.0]
                      (if (< j (aget ro (unchecked-inc v)))
                        (let [u (aget rt j)
                              od (unchecked-subtract (aget fo (unchecked-inc u)) (aget fo u))]
                          (if (pos? od)
                            (recur (unchecked-inc j) (+ s (/ (aget rank u) (double od))))
                            (recur (unchecked-inc j) s)))
                        s))]
            (aset rank2 v (+ base (* damping sum)))))
        (dotimes [i nn] (aset rank i (aget rank2 i)))))
    (for [i (range nn)]
      {:node (decode-id g i) :rank (aget rank i)})))

(defn page-rank
  "PageRank with positional arguments, matching flatiron's README:
     (page-rank graph)             ;; 20 iterations, damping 0.85
     (page-rank graph iters)
     (page-rank graph iters damping)"
  ([g] (pagerank g))
  ([g iters] (pagerank g :iters iters))
  ([g iters damping] (pagerank g :iters iters :damping damping)))

;; ── connected components ───────────────────────────────────────────────
(defn connected-components
  "The undirected components of the graph, via BFS flood fill over forward
   and reverse adjacency: O(V+E), one traversal per component. Returns rows
   of {:node n :component rep} where rep is the smallest node id in the
   component, decoded to its value."
  [^Graph g]
  (let [nn (.n-nodes g)
        ^longs fo (.fwd-offsets g) ^longs ft (.fwd-targets g)
        ^longs ro (.rev-offsets g) ^longs rt (.rev-targets g)
        label (long-array nn)]
    (java.util.Arrays/fill label -1)
    (dotimes [s nn]
      (when (== -1 (aget label s))
        (let [q (java.util.ArrayDeque.)]
          (.add q (Long/valueOf s))
          (aset label s s)
          (loop []
            (when-not (.isEmpty q)
              (let [u (long (.poll q))]
                (let [flood (fn [^longs off ^longs tgt]
                              (loop [j (aget off u)]
                                (when (< j (aget off (unchecked-inc u)))
                                  (let [v (aget tgt j)]
                                    (when (== -1 (aget label v))
                                      (aset label v s)
                                      (.add q (Long/valueOf v))))
                                  (recur (unchecked-inc j)))))]
                  (flood fo ft)
                  (flood ro rt))
                (recur)))))))
    (for [i (range nn)]
      {:node (decode-id g i) :component (decode-id g (aget label i))})))

;; ── db integration ────────────────────────────────────────────────────
(defn graph-of
  "Build a graph from a relatum db. Takes a table name or a full query
   (e.g [[:from :Follows] [:where [> :ts 100]]]); rows must carry :src and
   :dst (and optionally :weight). opts remaps columns:
   {:src :from :dst :to :weight :w}. See `graph` for semantics."
  [db source & [opts]]
  (let [{:keys [src dst weight] :or {src :src dst :dst weight :weight}} opts
        rows (if (vector? source) (rel/q db source) (rel/q db [[:from source]]))
        edges (map (fn [r]
                     (let [e {:src (src r) :dst (dst r)}]
                       (if-some [w (weight r)] (assoc e :weight w) e)))
                   rows)]
    (graph edges)))
