(ns learn.example.runtime-shlExact-overflow
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^:var alternating-bits (ak/u8 2r01010101)]
    (set! _ (ak/& alternating-bits))
    ;; Shifting by two would need more than eight bits.
    (let [shifted-bits (ak/shlExact alternating-bits 2)]
      (debug/print "value: {}\n" [shifted-bits]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
