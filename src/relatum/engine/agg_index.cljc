(ns ^:no-doc relatum.engine.agg-index)

(defrecord AggIndexNode [size rm cache a b reduced combined results])

(defprotocol AggIndex
  (index-row [idx row])
  (unindex-row [idx row])
  (contains-row? [idx row])
  (row-seqable [idx]))

(defn- combine-reduced [combine-fn combined reduced]
  (cond
    (nil? combined) reduced
    (nil? reduced) combined
    :else (delay (combine-fn @combined @reduced))))

(defn- combine-results [combine-fn tree1 tree2]
  (combine-reduced combine-fn (:results tree1) (:results tree2)))

(defn- push-cache
  [idx tree new-cache reduced]
  (let [{:keys [rm, size, a, b, cache] :or {size 0}} tree
        cache-size (:cache-size idx)
        combine-fn (:combine-fn idx)]
    (if (seq cache)
      (let [grow-a (< (:size a 0) (:size b 0))
            branch-id (if grow-a :a :b)
            a (if grow-a (push-cache idx a cache (:reduced tree)) a)
            b (if grow-a b (push-cache idx b cache (:reduced tree)))
            rm (persistent! (reduce-kv (fn [m k _] (assoc! m k branch-id)) (transient (or rm {})) cache))
            combined (combine-results combine-fn a b)]
        (->AggIndexNode
          (+ size cache-size)
          rm
          new-cache
          a
          b
          reduced
          combined
          (combine-reduced combine-fn combined reduced)))
      (let [combined (combine-results combine-fn a b)]
        (->AggIndexNode
          (+ size cache-size)
          rm
          new-cache
          a
          b
          reduced
          combined
          (combine-reduced combine-fn combined reduced))))))

(defn- add
  [idx tree row]
  (let [{:keys [size, rm, cache, a, b, reduced, combined] :or {size 0, cache {}}} tree
        cache-size (:cache-size idx)
        reduce-fn (:reduce-fn idx)
        combine-fn (:combine-fn idx)]
    (cond
      (contains? rm row) tree
      (contains? cache row) tree

      (< (count cache) cache-size)
      (let [new-cache (assoc cache row row)
            reduced (delay (reduce-fn (vals new-cache)))]
        (->AggIndexNode
          (inc size)
          rm
          new-cache
          a
          b
          reduced
          combined
          (combine-reduced combine-fn combined reduced)))

      :else
      (let [grow-a (< (:size a 0) (:size b 0))
            branch (if grow-a a b)
            branch-id (if grow-a :a :b)
            fit-cache (= 0 (mod (:size branch 0) cache-size))]
        (if fit-cache
          (let [branch (push-cache idx branch cache reduced)
                rm (persistent! (reduce-kv (fn [m k _] (assoc! m k branch-id)) (transient (or rm {})) cache))

                new-cache (array-map row row)
                reduced (delay (reduce-fn (vals new-cache)))
                a (if grow-a branch a)
                b (if grow-a b branch)
                combined (combine-results combine-fn a b)]
            (->AggIndexNode
              (inc size)
              rm
              new-cache
              a
              b
              reduced
              combined
              (combine-reduced combine-fn combined reduced)))
          (let [branch (add idx branch row)
                rm (assoc rm row branch-id)
                a (if grow-a branch a)
                b (if grow-a b branch)
                combined (combine-results combine-fn a b)]
            (->AggIndexNode
              (inc size)
              rm
              cache
              a
              b
              reduced
              combined
              (combine-reduced combine-fn combined reduced))))))))

(defn- shrink
  [tree]
  (if (<= (:size tree 0) 0)
    nil
    tree))

(defn- del
  [idx tree row]
  (let [{:keys [size
                rm
                cache
                a
                b
                combined
                reduced]} tree
        reduce-fn (:reduce-fn idx)
        combine-fn (:combine-fn idx)]
    (cond
      (contains? cache row)
      (let [new-cache (dissoc cache row)
            reduced (delay (reduce-fn (vals new-cache)))]
        (shrink
          (->AggIndexNode
            (dec size)
            rm
            new-cache
            a
            b
            reduced
            combined
            (combine-reduced combine-fn combined reduced))))

      (contains? rm row)
      (let [branch-id (rm row)
            shrink-a (= :a branch-id)
            new-rm (dissoc rm row)
            a (if shrink-a (del idx a row) a)
            b (if shrink-a b (del idx b row))
            combined (combine-results combine-fn a b)]
        (shrink
          (->AggIndexNode
            (dec size)
            new-rm
            cache
            a
            b
            reduced
            combined
            (combine-reduced combine-fn combined reduced))))

      :else tree)))

(defrecord GenericAggIndex [rowmap key-fn reduce-fn combine-fn complete-fn merge-fn cache-size]
  AggIndex
  (index-row [this row]
    (let [k (key-fn row)
          tree (get rowmap k)
          tree (add this tree row)
          irow (delay (merge-fn k (complete-fn @(:results tree))))
          tree (assoc tree :indexed-row irow)]
      (assoc this :rowmap (assoc rowmap k tree))))
  (unindex-row [this row]
    (let [k (key-fn row)
          tree (get rowmap k)
          tree (del this tree row)]
      (if (nil? tree)
        (assoc this :rowmap (dissoc rowmap k))
        (assoc this :rowmap (assoc rowmap k (assoc tree :indexed-row (delay (merge-fn k (complete-fn @(:results tree))))))))))
  (contains-row? [this row]
    (let [k (key-fn row)
          {:keys [rm cache]} (get rowmap k)]
      (or (contains? rm row)
          (contains? cache row))))
  (row-seqable [_]
    (mapcat keys (vals rowmap))))

(defn get-tree
  [idx k]
  (get (:rowmap idx) k))

(defn row-trees
  [idx]
  (vals (:rowmap idx)))

(defn index
  "The general aggregation index using a branching tree and combine-reduce. Hopefully will be a last resort
  as most aggs should have specialised indexes."
  [key-fn reduce-fn combine-fn complete-fn merge-fn cache-size]
  (->GenericAggIndex {} key-fn reduce-fn combine-fn complete-fn merge-fn cache-size))
