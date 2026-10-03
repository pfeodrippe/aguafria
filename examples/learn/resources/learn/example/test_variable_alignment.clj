(ns learn.example.test-variable-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest variable-alignment
  (let [x (k/var 1234 :i32)]
    (try (testing/expectEqual (a/type [:* :i32]) (k/TypeOf (k/& x))))
    (try (testing/expect
          (k/== (k/% (k/intFromPtr (k/& x)) (k/alignOf :i32)) 0)))
    ;; The implicitly-aligned pointer can be coerced to be explicitly-aligned to
    ;; the alignment of the underlying type `i32`:
    (let [ptr (k/as (k/& x) [:* {:align (k/alignOf :i32)} :i32])]
      (try (testing/expectEqual 1234 @ptr)))))

(comment
  (variable-alignment))
