(ns learn.example.test-variable-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest variable-alignment
  (let [x (k/var 1234 :i32)]
    (try (testing/expectEqual (az/type [:* :i32]) (k/TypeOf (k/& x))))
    (try (testing/expect
          (k/== (k/% (k/intFromPtr (k/& x)) (k/alignOf (az/type :i32))) 0)))
    ;; The implicitly-aligned pointer can be coerced to be explicitly-aligned to
    ;; the alignment of the underlying type `i32`:
    (let [ptr (k/as (k/& x) [:pointer {:align (k/alignOf (az/type :i32)), :size :one} :i32])]
      (try (testing/expectEqual 1234 @ptr)))))

(comment
  (variable-alignment))
