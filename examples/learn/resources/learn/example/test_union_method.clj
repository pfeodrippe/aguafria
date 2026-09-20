(ns learn.example.test-union-method
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Variant
  (az/union {:attrs #{:enum}}
    [[:int :i32]
     [:boolean :bool]
     [:none :void]
     (az/fn-decl truthy :bool [[self Variant]]
       (ak/return
        (ak/switch self
          (case [(az/field Variant :int)] [integer] (!= integer 0))
          (case [(az/field Variant :boolean)] [boolean] boolean)
          (case [(az/field Variant :none)] false))))]))

(az/deftest union-method-test
  (let [^:var integer (Variant {:int 1})
        ^:var boolean (Variant {:boolean false})
        ^:var empty (ak/as :.none Variant)]
    (try (testing/expect ((az/field integer :truthy))))
    (try (testing/expect (ak/! ((az/field boolean :truthy)))))
    (try (testing/expect (ak/! ((az/field empty :truthy)))))))

(comment
  (union-method-test))
