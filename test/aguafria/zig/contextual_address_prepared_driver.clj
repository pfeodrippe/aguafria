(ns aguafria.zig.contextual-address-prepared-driver
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(defn evaluate! []
  ;; The same ordinary bridge operations as the authored fixture body; never
  ;; substitute a call to its enclosing native function for preparation reuse.
  (with-open [slice (k/as (k/& [1 2 3 4]) [:slice-const :i32])]
    (a/value (a/get slice 2))))
