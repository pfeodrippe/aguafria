(ns learn.example.test-union-method
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defunion Variant {:attrs #{k/enum}}
  [[:int :i32]
   [:boolean :bool]
   ;; void can be omitted when inferring enum tag type.
   [:none :void]
   (a/fn- truthy :bool [[self Variant]]
          (k/switch self
            (:int Variant) (a/with-block :_ [x-int] (k/!= x-int 0))
            (:boolean Variant) (a/with-block :_ [x-bool] x-bool)
            (:none Variant) false))])

(a/deftest union-method
  (let [v1 (k/var (Variant {:int 1}))
        v2 (k/var (Variant {:boolean false}))
        v3 (k/var :none Variant)]
    (try (testing/expect ((:truthy v1))))
    (try (testing/expect (k/! ((:truthy v2)))))
    (try (testing/expect (k/! ((:truthy v3)))))))

(comment
  (union-method))
