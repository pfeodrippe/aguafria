(ns learn.examples.idiomatic-errors.runtime-shlExact-overflow
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^{:var :u8} alternating-bits 2r01010101]
    (set! _ (ak/& alternating-bits))
    ;; Shifting by two would need more than eight bits.
    (let [shifted-bits (ak/shlExact alternating-bits 2)]
      (debug/print "value: {}\n" [shifted-bits]))))
