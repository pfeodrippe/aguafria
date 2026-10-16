(ns learn.example.error-union-parsing-u64
  (:require [aguafria.keyword :as k]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- char-to-digit :u8 [[c :u8]]
  (k/switch c
    (k/... \0 \9) (k/- c \0)
    (k/... \A \Z) (k/+ (k/- c \A) 10)
    (k/... \a \z) (k/+ (k/- c \a) 10)
    (math/maxInt :u8)))

(a/defn parseU64 [:error-union :u64]
  [[buf [:slice-const :u8]] [radix :u8]]
  (let [x (k/var 0 :u64)]
    (k/for [c buf]
      (let [digit (char-to-digit c)]
        (when (k/>= digit radix)
          (k/return (a/error-value :InvalidChar)))
        ;; x *= radix
        (let [ov (k/var (k/mulWithOverflow x radix))]
          (when (k/!= (a/get ov 1) 0)
            (k/return (a/error-value :OverFlow)))
          ;; x += digit
          (k/= ov (k/addWithOverflow (a/get ov 0) digit))
          (when (k/!= (a/get ov 1) 0)
            (k/return (a/error-value :OverFlow)))
          (k/= x (a/get ov 0)))))
    x))

(a/deftest parse-u64
  (let [result (k/try (parseU64 "1234" 10))]
    (k/try (testing/expectEqual 1234 result))))

(comment
  (parse-u64))
