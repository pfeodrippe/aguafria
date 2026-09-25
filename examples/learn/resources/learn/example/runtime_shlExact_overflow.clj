(ns learn.example.runtime-shlExact-overflow
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [alternating-bits (k/var 2r01010101 :u8)]
    (k/= :_ (k/& alternating-bits))
    ;; Shifting by two would need more than eight bits.
    (let [shifted-bits (k/shlExact alternating-bits 2)]
      (debug/print "value: {}\n" [shifted-bits]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
