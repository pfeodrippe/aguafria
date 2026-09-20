(ns learn.example.test-comptime-call-extern-function
  (:require [aguafria.zig :as az]))

(az/defextern exit
  {:zig/prefix "extern"}
  :- :noreturn
  [])

(az/deftest comptime-extern-call-test
  (az/comptime-stmt
    (az/block
      (exit))))

(comment
  (comptime-extern-call-test))
