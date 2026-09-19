(ns learn.example.test-union-method
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Variant
  (az/container {:kind :union :attrs #{:enum}}
    (az/field-decl :int :i32)
    (az/field-decl :boolean :bool)
    (az/enum-field-decl :none)
    (az/fn-decl truthy :- :bool [[self Variant]]
      (ak/return
        (ak/switch self
          (case [(az/field Variant :int)] [integer] (!= integer 0))
          (case [(az/field Variant :boolean)] [boolean] boolean)
          (case [(az/field Variant :none)] false))))))

(az/deftest union-method-test
  (let [^{:var Variant} integer {:int 1}
        ^{:var Variant} boolean {:boolean false}
        ^{:var Variant} empty :.none]
    (try (testing/expect ((az/field integer :truthy))))
    (try (testing/expect (ak/! ((az/field boolean :truthy)))))
    (try (testing/expect (ak/! ((az/field empty :truthy)))))))
