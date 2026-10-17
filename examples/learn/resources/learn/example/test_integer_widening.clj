(ns learn.example.test-integer-widening
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest integer-widening
  (let [a (u8 250)
        b (u16 a)
        c (u32 b)
        d (u64 c)
        e (u64 d)
        f (u128 e)]
    (k/try (testing/expectEqual f a))))

(a/deftest implicit-unsigned-integer-to-signed-integer
  (let [a (u8 250)
        b (i16 a)]
    (k/try (testing/expectEqual 250 b))))

(a/deftest float-widening
  (let [a (f16 12.34)
        b (f32 a)
        c (f64 b)
        d (f128 c)]
    (k/try (testing/expectEqual d a))))

(comment
  (integer-widening)
  (implicit-unsigned-integer-to-signed-integer)
  (float-widening))
