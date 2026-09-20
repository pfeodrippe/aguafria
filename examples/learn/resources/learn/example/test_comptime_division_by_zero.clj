(ns learn.example.test-comptime-division-by-zero
  (:require [aguafria.zig :as az]))

(az/defcomptime reject-zero-divisor
  (let [^{:zig/type :i32} numerator 1
        ^{:zig/type :i32} denominator 0
        quotient (/ numerator denominator)]
    (set! _ quotient)))

(comment
  ;; Evaluate the comptime declaration above; it runs during native compilation, not at runtime.
  )
