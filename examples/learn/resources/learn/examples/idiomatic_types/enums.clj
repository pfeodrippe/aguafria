(ns learn.examples.idiomatic-types.enums
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Status
  (az/container {:kind :enum}
    (az/enum-field-decl :ok)
    (az/enum-field-decl :not_ok)))

(az/defconst success (az/field Status :ok))

(az/defconst Ordinal
  (az/container {:kind :enum :argument :u2}
    (az/enum-field-decl :zero)
    (az/enum-field-decl :one)
    (az/enum-field-decl :two)))

(az/deftest ordinal-values-test
  (try (testing/expectEqual 0 (ak/intFromEnum (az/field Ordinal :zero))))
  (try (testing/expectEqual 1 (ak/intFromEnum (az/field Ordinal :one))))
  (try (testing/expectEqual 2 (ak/intFromEnum (az/field Ordinal :two)))))

(az/defconst Magnitude
  (az/container {:kind :enum :argument :u32}
    (az/enum-field-decl :hundred 100)
    (az/enum-field-decl :thousand 1000)
    (az/enum-field-decl :million 1000000)))

(az/deftest explicit-ordinal-values-test
  (try (testing/expectEqual 100 (ak/intFromEnum (az/field Magnitude :hundred))))
  (try (testing/expectEqual 1000 (ak/intFromEnum (az/field Magnitude :thousand))))
  (try (testing/expectEqual 1000000 (ak/intFromEnum (az/field Magnitude :million)))))

(az/defconst MixedOrdinal
  (az/container {:kind :enum :argument :u4}
    (az/enum-field-decl :a)
    (az/enum-field-decl :b 8)
    (az/enum-field-decl :c)
    (az/enum-field-decl :d 4)
    (az/enum-field-decl :e)))

(az/deftest mixed-ordinal-values-test
  ;; An implicit tag continues counting from the preceding explicit value.
  (try (testing/expectEqual 0 (ak/intFromEnum (az/field MixedOrdinal :a))))
  (try (testing/expectEqual 8 (ak/intFromEnum (az/field MixedOrdinal :b))))
  (try (testing/expectEqual 9 (ak/intFromEnum (az/field MixedOrdinal :c))))
  (try (testing/expectEqual 4 (ak/intFromEnum (az/field MixedOrdinal :d))))
  (try (testing/expectEqual 5 (ak/intFromEnum (az/field MixedOrdinal :e)))))

(az/defconst Suit
  (az/container {:kind :enum}
    (az/enum-field-decl :clubs)
    (az/enum-field-decl :spades)
    (az/enum-field-decl :diamonds)
    (az/enum-field-decl :hearts)
    (az/fn-decl is-clubs {:attrs #{:public}} :- :bool [[self Suit]]
      (ak/return (== self (az/field Suit :clubs))))))

(az/deftest enum-method-test
  (let [suit (az/field Suit :spades)]
    (try (testing/expect (ak/! ((az/field suit :is-clubs)))))))

(az/defconst Kind
  (az/container {:kind :enum}
    (az/enum-field-decl :string)
    (az/enum-field-decl :number)
    (az/enum-field-decl :none)))

(az/deftest enum-switch-test
  (let [kind (az/field Kind :number)
        description (ak/switch kind
                      (case [(az/field Kind :string)] "this is a string")
                      (case [(az/field Kind :number)] "this is a number")
                      (case [(az/field Kind :none)] "this is a none"))]
    (try (testing/expectEqualStrings description "this is a number"))))

(az/defconst Small
  (az/container {:kind :enum}
    (az/enum-field-decl :one)
    (az/enum-field-decl :two)
    (az/enum-field-decl :three)
    (az/enum-field-decl :four)))

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
