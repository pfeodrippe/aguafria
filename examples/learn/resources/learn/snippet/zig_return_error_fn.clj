(ns learn.snippet.zig-return-error-fn
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; The compiler marks this helper as non-inline in LLVM IR.
(az/defn- record-error-return :void [[trace [:* StackTrace]]]
  (ak/= (az/index (az/field trace :instruction_addresses)
                  (az/field trace :index))
        (ak/returnAddress))
  (ak/= (az/field trace :index)
        (ak/% (+ (az/field trace :index) 1) N)))
