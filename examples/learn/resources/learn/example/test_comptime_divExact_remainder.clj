(ns learn.example.test-comptime-divExact-remainder
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; Exact division promises a zero remainder; ten divided by three violates it.
(az/defcomptime reject-inexact-division
  (let [^{:zig/type :u32} numerator 10
        ^{:zig/type :u32} denominator 3
        quotient (ak/divExact numerator denominator)]
    (set! _ quotient)))

(comment
  ;; Evaluate the comptime declaration above; it runs during native compilation, not at runtime.
  )
