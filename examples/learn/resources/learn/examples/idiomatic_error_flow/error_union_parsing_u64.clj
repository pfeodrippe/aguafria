(ns learn.examples.idiomatic-error-flow.error-union-parsing-u64
  (:require [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- char-to-digit :u8 [[character :u8]]
  (switch character
    (case [(az/op "..." \0 \9)] (- character \0))
    (case [(az/op "..." \A \Z)] (+ (- character \A) 10))
    (case [(az/op "..." \a \z)] (+ (- character \a) 10))
    (az/case-else (math/maxInt :u8))))

(az/defn parseU64 [:error-union :u64]
  [[text [:slice-const :u8]] [radix :u8]]
  (let [^{:var :u64} accumulated 0]
    (for [character text]
      (let [digit (char-to-digit character)]
        (when (>= digit radix)
          (ak/return (az/error-value :InvalidChar)))
        (let [product (ak/mulWithOverflow accumulated radix)]
          (when (ak/!= (az/index product 1) 0)
            (ak/return (az/error-value :OverFlow)))
          (let [sum (ak/addWithOverflow (az/index product 0) digit)]
            (when (ak/!= (az/index sum 1) 0)
              (ak/return (az/error-value :OverFlow)))
            (set! accumulated (az/index sum 0))))))
    accumulated))

(az/deftest parse-u64-test
  (let [number (try (parseU64 "1234" 10))]
    (try (testing/expectEqual 1234 number))))
