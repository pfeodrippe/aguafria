(ns learn.example.test-integer-widening
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-widening-test
  (let [a (ak/u8 250)
        b (ak/u16 a)
        c (ak/u32 b)
        d (ak/u64 c)
        e (ak/u64 d)
        f (ak/u128 e)]
    (try (testing/expectEqual f a))))

(az/deftest unsigned-to-signed-test
  (let [a (ak/u8 250)
        b (ak/i16 a)]
    (try (testing/expectEqual 250 b))))

(az/deftest float-widening-test
  (let [a (ak/f16 12.34)
        b (ak/f32 a)
        c (ak/f64 b)
        d (ak/f128 c)]
    (try (testing/expectEqual d a))))

(comment
  (integer-widening-test)
  (unsigned-to-signed-test)
  (float-widening-test))
