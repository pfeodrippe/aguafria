(ns learn.example.test-enums
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Enum :as enum-info]
            [aguafria.std.builtin.Type.EnumField :as field-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; Declare an enum.
(az/defenum Type
  [:ok
   :not_ok])

;; Declare a specific enum field.
(az/defconst c (:ok Type))

;; If you want access to the ordinal value of an enum, you
;; can specify the tag type.
(az/defenum Value
  {:argument :u2}
  [:zero
   :one
   :two])

;; Now you can cast between u2 and Value.
;; The ordinal value starts from 0, counting up by 1 from the previous member.
(az/deftest enum-ordinal-value
  (try (testing/expectEqual 0 (k/intFromEnum (:zero Value))))
  (try (testing/expectEqual 1 (k/intFromEnum (:one Value))))
  (try (testing/expectEqual 2 (k/intFromEnum (:two Value)))))

;; You can override the ordinal value for an enum.
(az/defenum Value2
  {:argument :u32}
  [[:hundred 100]
   [:thousand 1000]
   [:million 1000000]])

(az/deftest set-enum-ordinal-value
  (try (testing/expectEqual 100 (k/intFromEnum (:hundred Value2))))
  (try (testing/expectEqual 1000 (k/intFromEnum (:thousand Value2))))
  (try (testing/expectEqual 1000000 (k/intFromEnum (:million Value2)))))

;; You can also override only some values.
(az/defenum Value3
  {:argument :u4}
  [:a
   [:b 8]
   :c
   [:d 4]
   :e])

(az/deftest enum-implicit-ordinal-values-and-overridden-values
  (try (testing/expectEqual 0 (k/intFromEnum (:a Value3))))
  (try (testing/expectEqual 8 (k/intFromEnum (:b Value3))))
  (try (testing/expectEqual 9 (k/intFromEnum (:c Value3))))
  (try (testing/expectEqual 4 (k/intFromEnum (:d Value3))))
  (try (testing/expectEqual 5 (k/intFromEnum (:e Value3)))))

;; Enums can have methods, the same as structs and unions.
;; Enum methods are not special, they are only namespaced
;; functions that you can call with dot syntax.
(az/defenum Suit
  [:clubs
   :spades
   :diamonds
   :hearts
   (az/fn is-clubs :bool
     [[self Suit]]
     (k/== self (:clubs Suit)))])

(az/deftest enum-method
  (let [p (:spades Suit)]
    (try (testing/expect (k/! ((:is-clubs p)))))))

;; An enum can be switched upon.
(az/defenum Foo
  [:string
   :number
   :none])

(az/deftest enum-switch
  (let [p (:number Foo)
        what-is-it (k/switch p
                             (case [(:string Foo)] "this is a string")
                             (case [(:number Foo)] "this is a number")
                             (case [(:none Foo)] "this is a none"))]
    (try (testing/expectEqualStrings what-is-it "this is a number"))))

;; @typeInfo can be used to access the integer tag type of an enum.
(az/defenum Small
  [:one
   :two
   :three
   :four])

(az/deftest std-meta-Tag
  (try (testing/expectEqual (az/type :u2)
                            (enum-info/-tag_type (type-info/-enum (k/typeInfo Small))))))

;; @typeInfo tells us the field count and the fields names:
(az/deftest typeInfo
  (try (testing/expectEqual 4 (:len (enum-info/-fields (type-info/-enum (k/typeInfo Small))))))
  (try (testing/expectEqualStrings
        (field-info/-name (az/get (enum-info/-fields (type-info/-enum (k/typeInfo Small))) 1)) "two")))

;; @tagName gives a [:0]const u8 representation of an enum value:
(az/deftest tagName
  (try (testing/expectEqualStrings (k/tagName (:three Small)) "three")))

(comment
  (enum-ordinal-value)
  (set-enum-ordinal-value)
  (enum-implicit-ordinal-values-and-overridden-values)
  (enum-method)
  (enum-switch)
  (std-meta-Tag)
  (typeInfo)
  (tagName))
