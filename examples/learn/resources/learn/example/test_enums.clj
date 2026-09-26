(ns learn.example.test-enums
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Enum :as enum-info]
            [aguafria.std.builtin.Type.EnumField :as field-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum Type
  [:ok
   :not_ok])

(az/defconst success (:ok Type))

(az/defenum Value
  {:argument :u2}
  [:zero
   :one
   :two])

(az/deftest ordinal-values-test
  (try (testing/expectEqual 0 (k/intFromEnum (:zero Value))))
  (try (testing/expectEqual 1 (k/intFromEnum (:one Value))))
  (try (testing/expectEqual 2 (k/intFromEnum (:two Value)))))

(az/defenum Value2
  {:argument :u32}
  [[:hundred 100]
   [:thousand 1000]
   [:million 1000000]])

(az/deftest explicit-ordinal-values-test
  (try (testing/expectEqual 100 (k/intFromEnum (:hundred Value2))))
  (try (testing/expectEqual 1000 (k/intFromEnum (:thousand Value2))))
  (try (testing/expectEqual 1000000 (k/intFromEnum (:million Value2)))))

(az/defenum Value3
  {:argument :u4}
  [:a
   [:b 8]
   :c
   [:d 4]
   :e])

(az/deftest mixed-ordinal-values-test
  ;; An implicit tag continues counting from the preceding explicit value.
  (try (testing/expectEqual 0 (k/intFromEnum (:a Value3))))
  (try (testing/expectEqual 8 (k/intFromEnum (:b Value3))))
  (try (testing/expectEqual 9 (k/intFromEnum (:c Value3))))
  (try (testing/expectEqual 4 (k/intFromEnum (:d Value3))))
  (try (testing/expectEqual 5 (k/intFromEnum (:e Value3)))))

(az/defenum Suit
  [:clubs
   :spades
   :diamonds
   :hearts
   (az/fn is-clubs :bool
     [[self Suit]]
     (k/== self (:clubs Suit)))])

(az/deftest enum-method-test
  (let [suit (:spades Suit)]
    (try (testing/expect (k/! ((:is-clubs suit)))))))

(az/defenum Foo
  [:string
   :number
   :none])

(az/deftest enum-switch-test
  (let [kind (:number Foo)
        description (k/switch kind
                      (case [(:string Foo)] "this is a string")
                      (case [(:number Foo)] "this is a number")
                      (case [(:none Foo)] "this is a none"))]
    (try (testing/expectEqualStrings description "this is a number"))))

(az/defenum Small
  [:one
   :two
   :three
   :four])

(az/deftest enum-tag-type-test
  (let [information (type-info/-enum (k/typeInfo Small))]
    (try (testing/expectEqual (az/type :u2) (enum-info/-tag_type information)))))

(az/deftest enum-type-information-test
  (let [information (type-info/-enum (k/typeInfo Small))
        fields (enum-info/-fields information)]
    (try (testing/expectEqual 4 (:len fields)))
    (try (testing/expectEqualStrings (field-info/-name (az/get fields 1)) "two"))))

(az/deftest enum-tag-name-test
  (try (testing/expectEqualStrings (k/tagName (:three Small)) "three")))

(comment
  (ordinal-values-test)
  (explicit-ordinal-values-test)
  (mixed-ordinal-values-test)
  (enum-method-test)
  (enum-switch-test)
  (enum-tag-type-test)
  (enum-type-information-test)
  (enum-tag-name-test))
