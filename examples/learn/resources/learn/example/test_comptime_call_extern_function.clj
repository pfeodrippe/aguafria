(ns learn.example.test-comptime-call-extern-function
  (:require [aguafria.zig :as az]))

(az/defextern exit :noreturn
  {:zig/prefix "extern"}
  [])

(az/deftest comptime-extern-call-test
  (az/comptime-stmt
    (az/block
      (exit))))

(comment
  (comptime-extern-call-test))
