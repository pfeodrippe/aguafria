(ns learn.example.test-coerce-unions-enums
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defenum E
  [:one
   :two
   :three])

(a/defunion U {:type E}
  [[:one :i32]
   [:two :f32]
   [:three :void]])

(a/defunion U2 {:attrs #{enum}}
  [[:a :void]
   [:b :f32]
   (a/fn- tag :usize [[self U2]]
          (switch self
            :a 1
            :b 2))])

(a/deftest coercion-between-unions-and-enums
  (let [u (U {:two 12.34})
        e (as u E)] ; coerce union to enum
    (k/try (testing/expectEqual (:two E) e)))
  (let [three (:three E)
        u-2 (as three U)] ; coerce enum to union
    (k/try (testing/expectEqual (:three E) u-2)))
  (let [u-3 (as :three U)] ; coerce enum literal to union
    (k/try (testing/expectEqual (:three E) u-3)))
  (let [u-4 (as :a U2)] ; coerce enum literal to union with inferred enum tag type.
    (k/try (testing/expectEqual 1 ((:tag u-4)))))

  ;; The following example is invalid.
  ;; error: coercion from enum '@EnumLiteral()' to union 'test_coerce_unions_enum.U2' must initialize 'f32' field 'b'
  ;; var u_5: U2 = .b;
  ;; try expectEqual(2, u_5.tag());
  )

(comment
  (coercion-between-unions-and-enums))
