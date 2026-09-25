(ns learn.example.test-union-method
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Variant
  (az/union {:attrs #{k/enum}}
    [[:int :i32]
     [:boolean :bool]
     [:none :void]
     (az/fn- truthy :bool [[self Variant]]
       (k/switch self
         (case [(az/field Variant :int)] [integer] (k/!= integer 0))
         (case [(az/field Variant :boolean)] [boolean] boolean)
         (case [(az/field Variant :none)] false)))]))

(az/deftest union-method-test
  (let [integer (k/var (Variant {:int 1}))
        boolean (k/var (Variant {:boolean false}))
        empty (k/var :.none Variant)]
    (try (testing/expect ((az/field integer :truthy))))
    (try (testing/expect (k/! ((az/field boolean :truthy)))))
    (try (testing/expect (k/! ((az/field empty :truthy)))))))

(comment
  (union-method-test))
