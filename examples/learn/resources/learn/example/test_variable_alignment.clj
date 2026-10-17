(ns learn.example.test-variable-alignment
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest variable-alignment
  (let [x (k/var 1234 :i32)]
    (k/try (testing/expectEqual (a/type [:* :i32]) (TypeOf (k/& x))))
    (k/try (testing/expect
            (== (% (intFromPtr (k/& x)) (alignOf :i32)) 0)))
    ;; The implicitly-aligned pointer can be coerced to be explicitly-aligned to
    ;; the alignment of the underlying type `i32`:
    (let [ptr (as (k/& x) [:* {:align (alignOf :i32)} :i32])]
      (k/try (testing/expectEqual 1234 @ptr)))))

(comment
  (variable-alignment))
