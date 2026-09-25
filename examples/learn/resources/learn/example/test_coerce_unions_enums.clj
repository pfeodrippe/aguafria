(ns learn.example.test-coerce-unions-enums
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum E
  [:one
   :two
   :three])

(az/defconst U
  (az/union {:argument E}
    [[:one :i32]
     [:two :f32]
     [:three :void]]))

(az/defconst U2
  (az/union {:attrs #{k/enum}}
    [[:a :void]
     [:b :f32]
     (az/fn- tag :usize [[self U2]]
       (switch self
         (case [:.a] 1)
         (case [:.b] 2)))]))

(az/deftest union-enum-coercion-test
  (let [value (U {:two 12.34})
        tag (k/as value E)]
    (try (testing/expectEqual (az/field E :two) tag)))
  (let [empty-tag (az/field E :three)
        from-enum (k/as empty-tag U)
        from-literal (k/as :.three U)
        inferred (k/as :.a U2)]
    (try (testing/expectEqual (az/field E :three) from-enum))
    (try (testing/expectEqual (az/field E :three) from-literal))
    ;; A bare .b would be invalid: that variant requires an f32 payload.
    (try (testing/expectEqual 1 ((az/field inferred :tag))))))

(comment
  (union-enum-coercion-test))
