(ns learn.example.test-enums
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Enum :as enum-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

;; Declare an enum.
(a/defenum Type
  [:ok
   :not_ok])

;; Declare a specific enum field.
(a/defconst c (:ok Type))

;; If you want access to the ordinal value of an enum, you
;; can specify the tag type.
(a/defenum Value
  {:type :u2}
  [:zero
   :one
   :two])

;; Now you can cast between u2 and Value.
;; The ordinal value starts from 0, counting up by 1 from the previous member.
(a/deftest enum-ordinal-value
  (k/try (testing/expectEqual 0 (backingInt (:zero Value))))
  (k/try (testing/expectEqual 1 (backingInt (:one Value))))
  (k/try (testing/expectEqual 2 (backingInt (:two Value)))))

;; You can override the ordinal value for an enum.
(a/defenum Value2
  {:type :u32}
  [[:hundred 100]
   [:thousand 1000]
   [:million 1000000]])

(a/deftest set-enum-ordinal-value
  (k/try (testing/expectEqual 100 (backingInt (:hundred Value2))))
  (k/try (testing/expectEqual 1000 (backingInt (:thousand Value2))))
  (k/try (testing/expectEqual 1000000 (backingInt (:million Value2)))))

;; You can also override only some values.
(a/defenum Value3
  {:type :u4}
  [:a
   [:b 8]
   :c
   [:d 4]
   :e])

(a/deftest enum-implicit-ordinal-values-and-overridden-values
  (k/try (testing/expectEqual 0 (backingInt (:a Value3))))
  (k/try (testing/expectEqual 8 (backingInt (:b Value3))))
  (k/try (testing/expectEqual 9 (backingInt (:c Value3))))
  (k/try (testing/expectEqual 4 (backingInt (:d Value3))))
  (k/try (testing/expectEqual 5 (backingInt (:e Value3)))))

;; Enums can have methods, the same as structs and unions.
;; Enum methods are not special, they are only namespaced
;; functions that you can call with dot syntax.
(a/defenum Suit
  [:clubs
   :spades
   :diamonds
   :hearts
   (a/fn is-clubs :bool
     [[self Suit]]
     (== self (:clubs Suit)))])

(a/deftest enum-method
  (let [p (:spades Suit)]
    (k/try (testing/expect (! ((:is-clubs p)))))))

;; An enum can be switched upon.
(a/defenum Foo
  [:string
   :number
   :none])

(a/deftest enum-switch
  (let [p (:number Foo)
        what-is-it (switch p
                     (:string Foo) "this is a string"
                     (:number Foo) "this is a number"
                     (:none Foo) "this is a none")]
    (k/try (testing/expectEqualStrings what-is-it "this is a number"))))

;; @typeInfo can be used to access the integer tag type of an enum.
(a/defenum Small
  [:one
   :two
   :three
   :four])

(a/deftest std-meta-Tag
  (k/try (testing/expectEqual (a/type :u2)
                              (enum-info/-tag_type (type-info/-enum (k/typeInfo Small))))))

;; @typeInfo tells us the field count and the fields names:
(a/deftest typeInfo
  (k/try (testing/expectEqual 4 (:len (enum-info/-field_names (type-info/-enum (k/typeInfo Small))))))
  (k/try (testing/expectEqualStrings
          (a/get (enum-info/-field_names (type-info/-enum (k/typeInfo Small))) 1) "two")))

;; @tagName gives a [:0]const u8 representation of an enum value:
(a/deftest tagName
  (k/try (testing/expectEqualStrings (k/tagName (:three Small)) "three")))

;; Empty enums are uninstantiable, their tag type is always noreturn.
(a/defenum Empty [])

(a/deftest empty-enum
  (k/try (testing/expectEqual (a/type :noreturn)
                              (enum-info/-tag_type (type-info/-enum (k/typeInfo Empty))))))

(comment
  (enum-ordinal-value)
  (set-enum-ordinal-value)
  (enum-implicit-ordinal-values-and-overridden-values)
  (enum-method)
  (enum-switch)
  (std-meta-Tag)
  (typeInfo)
  (tagName)
  (empty-enum))
