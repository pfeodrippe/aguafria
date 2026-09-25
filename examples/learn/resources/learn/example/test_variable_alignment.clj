(ns learn.example.test-variable-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest variable-alignment-test
  (let [value (k/var 1234 :i32)]
    (try (testing/expectEqual (az/type [:* :i32]) (k/TypeOf (k/& value))))
    (try (testing/expect
          (k/== (az/op "%" (k/intFromPtr (k/& value)) (k/alignOf (az/type :i32))) 0)))
    ;; An explicit alignment equal to the natural alignment is the same guarantee.
    (let [pointer (k/as (k/& value) [:pointer {:align (k/alignOf (az/type :i32)), :size :one} :i32])]
      (try (testing/expectEqual 1234 @pointer)))))

(comment
  (variable-alignment-test))
