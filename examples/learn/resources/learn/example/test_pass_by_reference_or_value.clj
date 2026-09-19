(ns learn.example.test-pass-by-reference-or-value
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct Point [[:x :i32] [:y :i32]])

;; Zig may pass this value by reference or copy. Its parameter address is
;; valid only during the call, regardless of that implementation choice.
(az/defn sum-coordinates :i32 [[point Point]]
  (+ (az/field point :x) (az/field point :y)))

(az/deftest pass-struct-to-function-test
  (let [point (Point {:x 1 :y 2})]
    (try (testing/expectEqual 3 (sum-coordinates point)))))
