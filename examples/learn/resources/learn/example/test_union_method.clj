(ns learn.example.test-union-method
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Variant
  (az/union {:attrs #{k/enum}}
            [[:int :i32]
             [:boolean :bool]
     ;; void can be omitted when inferring enum tag type.
             [:none :void]
             (az/fn- truthy :bool [[self Variant]]
                     (k/switch self
                               (case [(:int Variant)] [x-int] (k/!= x-int 0))
                               (case [(:boolean Variant)] [x-bool] x-bool)
                               (case [(:none Variant)] false)))]))

(az/deftest union-method
  (let [v1 (k/var (Variant {:int 1}))
        v2 (k/var (Variant {:boolean false}))
        v3 (k/var :.none Variant)]
    (try (testing/expect ((:truthy v1))))
    (try (testing/expect (k/! ((:truthy v2)))))
    (try (testing/expect (k/! ((:truthy v3)))))))

(comment
  (union-method))
