(ns learn.example.test-splat-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest vector-splat-test
  (let [scalar (k/u32 5)
        repeated (k/as (k/splat scalar) [:vector 4 :u32])]
    (try (testing/expectEqualSlices
          (az/type :u32)
          (k/& (az/array-init [5 5 5 5] [:array :_ :u32]))
          (k/& (k/as repeated (az/type [:array 4 :u32])))))))

(az/deftest array-splat-test
  (let [scalar (k/u32 5)
        repeated (k/as (k/splat scalar) [:array 4 :u32])]
    (try (testing/expectEqualSlices
          (az/type :u32)
          (k/& (az/array-init [5 5 5 5] [:array :_ :u32]))
          (k/& (k/as repeated (az/type [:array 4 :u32])))))))

(comment
  (vector-splat-test)
  (array-splat-test))
