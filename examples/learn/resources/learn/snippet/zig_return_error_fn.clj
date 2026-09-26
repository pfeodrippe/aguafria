(ns learn.snippet.zig-return-error-fn
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

;; The compiler marks this helper as non-inline in LLVM IR.
(az/defn- record-error-return :void [[trace [:* StackTrace]]]
  (k/= (az/get-in trace [:instruction_addresses (:index trace)])
        (k/returnAddress))
  (k/= (:index trace)
        (k/% (k/+ (:index trace) 1) N)))
