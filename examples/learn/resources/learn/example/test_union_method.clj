(ns learn.example.test-union-method
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Variant
  (az/union {:attrs #{ak/enum}}
    [[:int :i32]
     [:boolean :bool]
     [:none :void]
     (az/fn- truthy :bool [[self Variant]]
       (ak/switch self
         (case [(az/field Variant :int)] [integer] (ak/!= integer 0))
         (case [(az/field Variant :boolean)] [boolean] boolean)
         (case [(az/field Variant :none)] false)))]))

(az/deftest union-method-test
  (let [integer (ak/var (Variant {:int 1}))
        boolean (ak/var (Variant {:boolean false}))
        empty (ak/var :.none Variant)]
    (try (testing/expect ((az/field integer :truthy))))
    (try (testing/expect (ak/! ((az/field boolean :truthy)))))
    (try (testing/expect (ak/! ((az/field empty :truthy)))))))

(comment
  (union-method-test))
