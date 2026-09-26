(ns learn.example.test-integer-widening
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-widening
  (let [a (k/u8 250)
        b (k/u16 a)
        c (k/u32 b)
        d (k/u64 c)
        e (k/u64 d)
        f (k/u128 e)]
    (try (testing/expectEqual f a))))

(az/deftest implicit-unsigned-integer-to-signed-integer
  (let [a (k/u8 250)
        b (k/i16 a)]
    (try (testing/expectEqual 250 b))))

(az/deftest float-widening
  (let [a (k/f16 12.34)
        b (k/f32 a)
        c (k/f64 b)
        d (k/f128 c)]
    (try (testing/expectEqual d a))))

(comment
  (integer-widening)
  (implicit-unsigned-integer-to-signed-integer)
  (float-widening))
