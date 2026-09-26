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
         (case [(:int Variant)] [integer] (k/!= integer 0))
         (case [(:boolean Variant)] [boolean] boolean)
         (case [(:none Variant)] false)))]))

(az/deftest union-method-test
  (let [integer (k/var (Variant {:int 1}))
        boolean (k/var (Variant {:boolean false}))
        empty (k/var :.none Variant)]
    (try (testing/expect ((:truthy integer))))
    (try (testing/expect (k/! ((:truthy boolean)))))
    (try (testing/expect (k/! ((:truthy empty)))))))

(comment
  (union-method-test))
