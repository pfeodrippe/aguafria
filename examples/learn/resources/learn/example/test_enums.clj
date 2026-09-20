(ns learn.example.test-enums
  (:require [aguafria.keyword :as ak]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Enum :as enum-info]
            [aguafria.std.builtin.Type.EnumField :as field-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum Type
  [:ok
   :not_ok])

(az/defconst success (az/field Type :ok))

(az/defenum Value
  {:argument :u2}
  [:zero
   :one
   :two])

(az/deftest ordinal-values-test
  (try (testing/expectEqual 0 (ak/intFromEnum (az/field Value :zero))))
  (try (testing/expectEqual 1 (ak/intFromEnum (az/field Value :one))))
  (try (testing/expectEqual 2 (ak/intFromEnum (az/field Value :two)))))

(az/defenum Value2
  {:argument :u32}
  [[:hundred 100]
   [:thousand 1000]
   [:million 1000000]])

(az/deftest explicit-ordinal-values-test
  (try (testing/expectEqual 100 (ak/intFromEnum (az/field Value2 :hundred))))
  (try (testing/expectEqual 1000 (ak/intFromEnum (az/field Value2 :thousand))))
  (try (testing/expectEqual 1000000 (ak/intFromEnum (az/field Value2 :million)))))

(az/defenum Value3
  {:argument :u4}
  [:a
   [:b 8]
   :c
   [:d 4]
   :e])

(az/deftest mixed-ordinal-values-test
  ;; An implicit tag continues counting from the preceding explicit value.
  (try (testing/expectEqual 0 (ak/intFromEnum (az/field Value3 :a))))
  (try (testing/expectEqual 8 (ak/intFromEnum (az/field Value3 :b))))
  (try (testing/expectEqual 9 (ak/intFromEnum (az/field Value3 :c))))
  (try (testing/expectEqual 4 (ak/intFromEnum (az/field Value3 :d))))
  (try (testing/expectEqual 5 (ak/intFromEnum (az/field Value3 :e)))))

(az/defenum Suit
  [:clubs
   :spades
   :diamonds
   :hearts
   (az/fn-decl is-clubs :bool {:attrs #{:public}} [[self Suit]]
     (ak/return (ak/== self (az/field Suit :clubs))))])

(az/deftest enum-method-test
  (let [suit (az/field Suit :spades)]
    (try (testing/expect (ak/! ((az/field suit :is-clubs)))))))

(az/defenum Foo
  [:string
   :number
   :none])

(az/deftest enum-switch-test
  (let [kind (az/field Foo :number)
        description (ak/switch kind
                      (case [(az/field Foo :string)] "this is a string")
                      (case [(az/field Foo :number)] "this is a number")
                      (case [(az/field Foo :none)] "this is a none"))]
    (try (testing/expectEqualStrings description "this is a number"))))

(az/defenum Small
  [:one
   :two
   :three
   :four])

(az/deftest enum-tag-type-test
  (let [information (type-info/-enum (ak/typeInfo Small))]
    (try (testing/expectEqual (az/type :u2) (enum-info/-tag_type information)))))

(az/deftest enum-type-information-test
  (let [information (type-info/-enum (ak/typeInfo Small))
        fields (enum-info/-fields information)]
    (try (testing/expectEqual 4 (az/field fields :len)))
    (try (testing/expectEqualStrings (field-info/-name (az/index fields 1)) "two"))))

(az/deftest enum-tag-name-test
  (try (testing/expectEqualStrings (ak/tagName (az/field Small :three)) "three")))

(comment
  (ordinal-values-test)
  (explicit-ordinal-values-test)
  (mixed-ordinal-values-test)
  (enum-method-test)
  (enum-switch-test)
  (enum-tag-type-test)
  (enum-type-information-test)
  (enum-tag-name-test))
