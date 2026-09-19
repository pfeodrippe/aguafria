(ns learn.example.test-integer-widening
  (:require aguafria.std
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-widening-test
  (let [^{:zig/type :u8} a 250
        ^{:zig/type :u16} b a
        ^{:zig/type :u32} c b
        ^{:zig/type :u64} d c
        ^{:zig/type :u64} e d
        ^{:zig/type :u128} f e]
    (try (testing/expectEqual f a))))

(az/deftest unsigned-to-signed-test
  (let [^{:zig/type :u8} a 250
        ^{:zig/type :i16} b a]
    (try (testing/expectEqual 250 b))))

(az/deftest float-widening-test
  (let [^{:zig/type :f16} a 12.34
        ^{:zig/type :f32} b a
        ^{:zig/type :f64} c b
        ^{:zig/type :f128} d c]
    (try (testing/expectEqual d a))))
