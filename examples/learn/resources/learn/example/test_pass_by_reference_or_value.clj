(ns learn.example.test-pass-by-reference-or-value
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct Point [[:x :i32] [:y :i32]])

(az/defn foo :i32 [[point Point]]
  ;; Here, `point` could be a reference, or a copy. The function body
  ;; can ignore the difference and treat it as a value. Be very careful
  ;; taking the address of the parameter - it should be treated as if
  ;; the address will become invalid when the function returns.
  (k/+ (:x point) (:y point)))

(az/deftest pass-struct-to-function
  (try (testing/expectEqual 3 (foo (Point {:x 1 :y 2})))))

(comment
  (pass-struct-to-function))
