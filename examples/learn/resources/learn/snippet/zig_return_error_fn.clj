(ns learn.snippet.zig-return-error-fn
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

;; The compiler marks this helper as non-inline in LLVM IR.
(az/defn- record-error-return :void [[trace [:* StackTrace]]]
  (k/= (az/index (az/field trace :instruction_addresses)
                  (az/field trace :index))
        (k/returnAddress))
  (k/= (az/field trace :index)
        (k/% (k/+ (az/field trace :index) 1) N)))
