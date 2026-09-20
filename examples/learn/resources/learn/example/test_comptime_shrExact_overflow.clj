(ns learn.example.test-comptime-shrExact-overflow
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-lost-low-bits
  (let [^{:zig/type :u8} alternating-bits 2r10101010
        shifted-bits (ak/shrExact alternating-bits 2)]
    (set! _ shifted-bits)))

(comment
  ;; Evaluate the comptime declaration above; it runs during native compilation, not at runtime.
  )
