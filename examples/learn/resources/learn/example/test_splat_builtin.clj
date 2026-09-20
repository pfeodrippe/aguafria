(ns learn.example.test-splat-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest vector-splat-test
  (let [^{:zig/type :u32} scalar 5
        ^{:zig/type [:vector 4 :u32]} repeated (ak/splat scalar)]
    (try (testing/expectEqualSlices
          (az/type :u32)
          (& (az/array-init [:array _ :u32] [5 5 5 5]))
          (& (ak/as (az/type [:array 4 :u32]) repeated))))))

(az/deftest array-splat-test
  (let [^{:zig/type :u32} scalar 5
        ^{:zig/type [:array 4 :u32]} repeated (ak/splat scalar)]
    (try (testing/expectEqualSlices
          (az/type :u32)
          (& (az/array-init [:array _ :u32] [5 5 5 5]))
          (& (ak/as (az/type [:array 4 :u32]) repeated))))))

(comment
  (vector-splat-test)
  (array-splat-test))
