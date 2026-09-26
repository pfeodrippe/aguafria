(ns learn.example.error-union-parsing-u64
  (:require [aguafria.keyword :as k]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- char-to-digit :u8 [[c :u8]]
  (switch c
          (case [(k/... \0 \9)] (k/- c \0))
          (case [(k/... \A \Z)] (k/+ (k/- c \A) 10))
          (case [(k/... \a \z)] (k/+ (k/- c \a) 10))
          (az/case-else (math/maxInt :u8))))

(az/defn parseU64 [:error-union :u64]
  [[buf [:slice-const :u8]] [radix :u8]]
  (let [x (k/var 0 :u64)]
    (k/for [c buf]
      (let [digit (char-to-digit c)]
        (when (k/>= digit radix)
          (k/return (az/error-value :InvalidChar)))
        ;; x *= radix
        (let [ov (k/var (k/mulWithOverflow x radix))]
          (when (k/!= (az/get ov 1) 0)
            (k/return (az/error-value :OverFlow)))
          ;; x += digit
          (k/= ov (k/addWithOverflow (az/get ov 0) digit))
          (when (k/!= (az/get ov 1) 0)
            (k/return (az/error-value :OverFlow)))
          (k/= x (az/get ov 0)))))
    x))

(az/deftest parse-u64
  (let [result (try (parseU64 "1234" 10))]
    (try (testing/expectEqual 1234 result))))

(comment
  (parse-u64))
