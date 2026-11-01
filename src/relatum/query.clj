(ns relatum.query
  "Single-arity query surface over a relatum database.

  A relatum database is a value, so `rows` and `insert` are pure: no registry, no
  mutation. They exist because the engine's own `q` and `transact` are variadic
  and multi-arity, and a signature can only be given to a fn with one arity."
  (:require [relatum.engine.dataflow :as dataflow]))

(defn rows
  [db query]
  (dataflow/q db query))

(defn insert
  [db command]
  (dataflow/transact db [command]))
