(ns learn.examples.idiomatic-metaprogramming.inline-switch-union-tag
  "Converted from test_inline_switch_union_tag.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst NumericValue
  (az/container {:kind :union :attrs #{:enum}}
    (az/field-decl :a :u32)
    (az/field-decl :b :f32)))

(az/defn- as-integer :u32 [[value NumericValue]]
  (switch value
    (az/inline-case-else [number tag]
      (if (== tag :.b)
        (ak/intFromFloat number)
        number))))

(az/deftest inline-union-tag-test
  (let [value (az/init NumericValue {:b 42})]
    (try (testing/expectEqual 42 (as-integer value)))))
