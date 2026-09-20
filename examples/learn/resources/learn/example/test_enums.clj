(ns learn.example.test-enums
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum Status
  [:ok
   :not_ok])

(az/defconst success (az/field Status :ok))

(az/defenum Ordinal
  {:argument :u2}
  [:zero
   :one
   :two])

(az/deftest ordinal-values-test
  (try (testing/expectEqual 0 (ak/intFromEnum (az/field Ordinal :zero))))
  (try (testing/expectEqual 1 (ak/intFromEnum (az/field Ordinal :one))))
  (try (testing/expectEqual 2 (ak/intFromEnum (az/field Ordinal :two)))))

(az/defenum Magnitude
  {:argument :u32}
  [[:hundred 100]
   [:thousand 1000]
   [:million 1000000]])

(az/deftest explicit-ordinal-values-test
  (try (testing/expectEqual 100 (ak/intFromEnum (az/field Magnitude :hundred))))
  (try (testing/expectEqual 1000 (ak/intFromEnum (az/field Magnitude :thousand))))
  (try (testing/expectEqual 1000000 (ak/intFromEnum (az/field Magnitude :million)))))

(az/defenum MixedOrdinal
  {:argument :u4}
  [:a
   [:b 8]
   :c
   [:d 4]
   :e])

(az/deftest mixed-ordinal-values-test
  ;; An implicit tag continues counting from the preceding explicit value.
  (try (testing/expectEqual 0 (ak/intFromEnum (az/field MixedOrdinal :a))))
  (try (testing/expectEqual 8 (ak/intFromEnum (az/field MixedOrdinal :b))))
  (try (testing/expectEqual 9 (ak/intFromEnum (az/field MixedOrdinal :c))))
  (try (testing/expectEqual 4 (ak/intFromEnum (az/field MixedOrdinal :d))))
  (try (testing/expectEqual 5 (ak/intFromEnum (az/field MixedOrdinal :e)))))

(az/defenum Suit
  [:clubs
   :spades
   :diamonds
   :hearts
   (az/fn-decl is-clubs :bool {:attrs #{:public}} [[self Suit]]
     (ak/return (== self (az/field Suit :clubs))))])

(az/deftest enum-method-test
  (let [suit (az/field Suit :spades)]
    (try (testing/expect (ak/! ((az/field suit :is-clubs)))))))

(az/defenum Kind
  [:string
   :number
   :none])

(az/deftest enum-switch-test
  (let [kind (az/field Kind :number)
        description (ak/switch kind
                      (case [(az/field Kind :string)] "this is a string")
                      (case [(az/field Kind :number)] "this is a number")
                      (case [(az/field Kind :none)] "this is a none"))]
    (try (testing/expectEqualStrings description "this is a number"))))

(az/defenum Small
  [:one
   :two
   :three
   :four])

(az/deftest enum-tag-type-test
  (let [information (az/field (ak/typeInfo Small) :enum)]
    (try (testing/expectEqual (az/type :u2) (az/field information :tag_type)))))

(az/deftest enum-type-information-test
  (let [information (az/field (ak/typeInfo Small) :enum)
        fields (az/field information :fields)]
    (try (testing/expectEqual 4 (az/field fields :len)))
    (try (testing/expectEqualStrings (az/field (az/index fields 1) :name) "two"))))

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
