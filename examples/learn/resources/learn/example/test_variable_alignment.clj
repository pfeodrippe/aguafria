(ns learn.example.test-variable-alignment
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest variable-alignment-test
  (let [^:var value (ak/i32 1234)]
    (try (testing/expectEqual (az/type [:* :i32]) (ak/TypeOf (& value))))
    (try (testing/expect
          (== (az/op "%" (ak/intFromPtr (& value)) (ak/alignOf (az/type :i32))) 0)))
    ;; An explicit alignment equal to the natural alignment is the same guarantee.
    (let [pointer (ak/as (& value) [:pointer {:align (ak/alignOf (az/type :i32)), :size :one} :i32])]
      (try (testing/expectEqual 1234 @pointer)))))

(comment
  (variable-alignment-test))
