(ns learn.example.test-splat-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest vector-splat-test
  (let [scalar (ak/u32 5)
        repeated (ak/as (ak/splat scalar) [:vector 4 :u32])]
    (try (testing/expectEqualSlices
          (az/type :u32)
          (& (az/array-init [5 5 5 5] [:array :_ :u32]))
          (& (ak/as repeated (az/type [:array 4 :u32])))))))

(az/deftest array-splat-test
  (let [scalar (ak/u32 5)
        repeated (ak/as (ak/splat scalar) [:array 4 :u32])]
    (try (testing/expectEqualSlices
          (az/type :u32)
          (& (az/array-init [5 5 5 5] [:array :_ :u32]))
          (& (ak/as repeated (az/type [:array 4 :u32])))))))

(comment
  (vector-splat-test)
  (array-splat-test))
