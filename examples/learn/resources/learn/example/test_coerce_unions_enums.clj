(ns learn.example.test-coerce-unions-enums
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum Tag
  [:one
   :two
   :three])

(az/defconst Value
  (az/union {:argument Tag}
    [[:one :i32]
     [:two :f32]
     [:three :void]]))

(az/defconst InferredTagValue
  (az/union {:attrs #{:enum}}
    [[:a :void]
     [:b :f32]
     (az/fn-decl tag :usize [[self InferredTagValue]]
       (switch self
         (case [:.a] 1)
         (case [:.b] 2)))]))

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

(comment
  (union-enum-coercion-test))
