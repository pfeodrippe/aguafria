(ns learn.snippet.zig-return-error-fn
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

;; The compiler marks this helper as non-inline in LLVM IR.
(a/defn- record-error-return :void [[trace [:* StackTrace]]]
  (= (a/get-in trace [:instruction_addresses (:index trace)])
     (returnAddress))
  (= (:index trace)
     (% (+ (:index trace) 1) N)))
