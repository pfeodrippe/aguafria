(ns learn.example.error-union-parsing-u64
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- char-to-digit :u8 [[c :u8]]
  (switch c
    (k/... \0 \9) (- c \0)
    (k/... \A \Z) (+ (- c \A) 10)
    (k/... \a \z) (+ (- c \a) 10)
    (math/maxInt :u8)))

(a/defn parseU64 [:error-union :u64]
  [[buf [:slice-const :u8]] [radix :u8]]
  (let [x (k/var 0 :u64)]
    (for [c buf]
      (let [digit (char-to-digit c)]
        (when (>= digit radix)
          (return (a/error-value :InvalidChar)))
        ;; x *= radix
        (let [ov (k/var (mulWithOverflow x radix))]
          (when (!= (a/get ov 1) 0)
            (return (a/error-value :OverFlow)))
          ;; x += digit
          (= ov (addWithOverflow (a/get ov 0) digit))
          (when (!= (a/get ov 1) 0)
            (return (a/error-value :OverFlow)))
          (= x (a/get ov 0)))))
    x))

(a/deftest parse-u64
  (let [result (k/try (parseU64 "1234" 10))]
    (k/try (testing/expectEqual 1234 result))))

(comment
  (parse-u64))
