(ns learn.snippet.zig-return-error-fn
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

;; The compiler marks this helper as non-inline in LLVM IR.
(a/defn- record-error-return :void [[trace [:* StackTrace]]]
  (k/= (a/get-in trace [:instruction_addresses (:index trace)])
        (k/returnAddress))
  (k/= (:index trace)
        (k/% (k/+ (:index trace) 1) N)))
