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

(az/deftest coercion-between-unions-and-enums
  (let [u (U {:two 12.34})
        e (k/as u E)] ; coerce union to enum
    (try (testing/expectEqual (:two E) e)))
  (let [three (:three E)
        u-2 (k/as three U)] ; coerce enum to union
    (try (testing/expectEqual (:three E) u-2)))
  (let [u-3 (k/as :.three U)] ; coerce enum literal to union
    (try (testing/expectEqual (:three E) u-3)))
  (let [u-4 (k/as :.a U2)] ; coerce enum literal to union with inferred enum tag type.
    (try (testing/expectEqual 1 ((:tag u-4)))))

  ;; The following example is invalid.
  ;; error: coercion from enum '@EnumLiteral()' to union 'test_coerce_unions_enum.U2' must initialize 'f32' field 'b'
  ;; var u_5: U2 = .b;
  ;; try expectEqual(2, u_5.tag());
  )

(comment
  (coercion-between-unions-and-enums))
