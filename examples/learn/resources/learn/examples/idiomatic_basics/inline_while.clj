(ns learn.examples.idiomatic-basics.inline-while
  "Converted from test_inline_while.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest inline-while-test
  (let [^{:var true :zig/prefix "comptime"} index 0
        ^{:var :usize} sum 0]
    (az/while-loop {:inline? true
                    :continue (az/assign-expr "+=" index 1)}
      (< index 3)
      (let [T (ak/switch index
                (case [0] :f32)
                (case [1] :i8)
                (case [2] :bool)
                (az/case-else (ak/unreachable)))]
        (ak/+= sum (type-name-length T))))
    (try (testing/expectEqual 9 sum))))

(az/defn- type-name-length :usize
  [[T {:zig/prefix "comptime"} :type]]
  (az/field (ak/typeName T) :len))
