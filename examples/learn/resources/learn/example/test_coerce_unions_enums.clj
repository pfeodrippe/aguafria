(ns learn.example.test-coerce-unions-enums
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Tag
  (az/container {:kind :enum}
    (az/enum-field-decl :one)
    (az/enum-field-decl :two)
    (az/enum-field-decl :three)))

(az/defconst Value
  (az/container {:kind :union :argument Tag}
    (az/field-decl :one :i32)
    (az/field-decl :two :f32)
    (az/enum-field-decl :three)))

(az/defconst InferredTagValue
  (az/container {:kind :union :attrs #{:enum}}
    (az/field-decl :a :void)
    (az/field-decl :b :f32)
    (az/fn-decl tag :- :usize [[self InferredTagValue]]
      (switch self
        (case [:.a] 1)
        (case [:.b] 2)))))

(az/deftest union-enum-coercion-test
  (let [value (az/init Value {:two 12.34})
        ^{:zig/type Tag} tag value]
    (try (testing/expectEqual (az/field Tag :two) tag)))
  (let [empty-tag (az/field Tag :three)
        ^{:zig/type Value} from-enum empty-tag
        ^{:zig/type Value} from-literal :.three
        ^{:zig/type InferredTagValue} inferred :.a]
    (try (testing/expectEqual (az/field Tag :three) from-enum))
    (try (testing/expectEqual (az/field Tag :three) from-literal))
    ;; A bare .b would be invalid: that variant requires an f32 payload.
    (try (testing/expectEqual 1 ((az/field inferred :tag))))))
