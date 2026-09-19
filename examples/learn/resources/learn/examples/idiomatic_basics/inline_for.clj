(ns learn.examples.idiomatic-basics.inline-for
  "Converted from test_inline_for.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest inline-for-test
  (let [numbers (az/array-init [:array _ :i32] [2 4 6])
        ^{:var :usize} sum 0]
    (az/inline-for [number numbers]
      (let [T (ak/switch number
                (case [2] :f32)
                (case [4] :i8)
                (case [6] :bool)
                (az/case-else (ak/unreachable)))]
        (ak/+= sum (type-name-length T))))
    (try (testing/expectEqual 9 sum))))

(az/defn- type-name-length :usize
  [[T {:zig/prefix "comptime"} :type]]
  (az/field (ak/typeName T) :len))
