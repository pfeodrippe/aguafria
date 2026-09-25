(ns learn.example.test-comptime-call-extern-function
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defextern exit :noreturn
  [])

(az/deftest comptime-extern-call-test
  (az/comptime-stmt
    (az/block
      (exit))))

(comment
  (comptime-extern-call-test))
