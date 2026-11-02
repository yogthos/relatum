# Graphs

relatum.graph is a graph engine onto the relatum database. It runs BFS, DFS, Dijkstra, PageRank, connected components, shortest path over a CSR (compressed sparse row) layout in flat long arrays, so a build is O(V+E) and every traversal is a tight loop over primitives.

## Building a graph

The boundary takes edge rows. `graph-of` reads a table or a full query
result from the database; `graph` takes any seq of edge maps:

```clojure
(require '[relatum.graph :as g])

(g/graph [{:src :alice :dst :bob}
          {:src :bob :dst :carol}])

;; from the database — rows must carry :src/:dst (or remap, below)
(g/graph-of db :Follows)
(g/graph-of db [[:from :Follows] [:where [> :ts 100]]])

;; non-default columns: {:src :from :dst :to :weight :w}
(g/graph-of db :Link {:src :from :dst :to :weight :w})
```

Integer node values are used as ids directly (node count is max id + 1);
any other node value is dictionary-encoded — interned to a dense id in
first-seen order and decoded on the way out. This is why keywords, uuids,
and strings work as nodes without any per-node hashing during traversal.

Construction validates each edge and throws ex-info (`:relatum/error`
`:missing-endpoint`, `:negative-node`, or `:bad-weight`) rather than
building broken topology: both endpoints must be present, integer endpoints
are ids and so must be nonnegative, and weights must be finite and
nonnegative (Dijkstra does not terminate on negative cycles).

```clojure
(let [gr (g/graph [{:src :a :dst :b} {:src :b :dst :c}])]
  (g/nodes gr)) ;; => [:a :b :c]
```

`graph-of` reads its edges once and materializes nothing; graphs are values
like the database itself, so a graph built from one db snapshot answers
queries about that snapshot forever. Rebuild after a transact.

## Reading a graph

```clojure
(g/n-nodes gr)         ;; node count
(g/n-edges gr)         ;; edge count
(g/nodes gr)           ;; every node value, in interned order
(g/degree gr :a)       ;; out-degree
(g/in-degree gr :a)    ;; in-degree
(g/neighbors gr :a)    ;; ([target weight] ...) along out-edges
```

## Traversing

BFS returns hop distance from a start node; unreachable nodes carry nil:

```clojure
(g/bfs gr :a)
;; => ({:node :a, :distance 0} {:node :b, :distance 1} {:node :c, :distance 2})
```

DFS returns the preorder visitation index of each reachable node:

```clojure
(g/dfs gr :a)
;; => ({:node :a, :order 0} {:node :b, :order 1} {:node :c, :order 2})
```

`shortest-path` returns the actual route, fewest hops when unweighted and
cheapest when any edge carries a `:weight`:

```clojure
(g/shortest-path gr :a :c)
;; => {:path [:a :b :c], :distance 2.0}
```

nil comes back when the destination is unreachable.

## Weighted graphs

An edge with a `:weight` makes the whole graph weighted; edges without one
default to 1.0. Dijkstra runs over the weights:

```clojure
(def wgr (g/graph [{:src :a :dst :b :weight 1.0}
                   {:src :b :dst :c :weight 1.0}
                   {:src :a :dst :c :weight 5.0}]))

(g/dijkstra wgr :a)
;; => ({:node :a, :distance 0.0} {:node :b, :distance 1.0} {:node :c, :distance 2.0})

(g/shortest-path wgr :a :c)
;; => {:path [:a :b :c], :distance 2.0}
```

## PageRank and components

```clojure
(g/pagerank gr :iters 30)          ;; {:node n :rank r}, ranks sum to ~1
(g/page-rank gr 30 0.85)           ;; positional form
(g/connected-components gr)        ;; {:node n :component rep}
```

`connected-components` treats edges as undirected (it propagates labels over
both forward and reverse adjacency) and reports each component by its
lowest-id member, decoded to its node value.

## Transacting results back

Every result row is a plain map, so analysis output can be inserted into the
database and joined against other tables:

```clojure
(-> db
    (rc/insert-many :Distances (g/bfs gr :a))
    (rc/insert-many :Ranks (g/pagerank gr)))
```

(This requires those tables to be declared with matching columns; see
[writing-data.md](writing-data.md).)
