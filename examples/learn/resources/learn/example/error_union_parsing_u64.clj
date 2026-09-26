(ns learn.example.error-union-parsing-u64
  (:require [aguafria.keyword :as k]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- char-to-digit :u8 [[character :u8]]
  (switch character
    (case [(k/... \0 \9)] (k/- character \0))
    (case [(k/... \A \Z)] (k/+ (k/- character \A) 10))
    (case [(k/... \a \z)] (k/+ (k/- character \a) 10))
    (az/case-else (math/maxInt :u8))))

(az/defn parseU64 [:error-union :u64]
  [[text [:slice-const :u8]] [radix :u8]]
  (let [accumulated (k/var 0 :u64)]
    (k/for [character text]
      (let [digit (char-to-digit character)]
        (when (k/>= digit radix)
          (k/return (az/error-value :InvalidChar)))
        (let [product (k/mulWithOverflow accumulated radix)]
          (when (k/!= (az/get product 1) 0)
            (k/return (az/error-value :OverFlow)))
          (let [sum (k/addWithOverflow (az/get product 0) digit)]
            (when (k/!= (az/get sum 1) 0)
              (k/return (az/error-value :OverFlow)))
            (k/= accumulated (az/get sum 0))))))
    accumulated))

(az/deftest parse-u64-test
  (let [number (try (parseU64 "1234" 10))]
    (try (testing/expectEqual 1234 number))))

(comment
  (parse-u64-test))
