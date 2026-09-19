(ns learn.examples.idiomatic-interop.comptime-call-extern-function
  "Converted from test_comptime_call_extern_function.zig"
  (:require [aguafria.zig :as az]))

(az/defextern exit
  {:zig/prefix "extern"}
  :- :noreturn
  [])

(az/deftest comptime-extern-call-test
  (az/comptime-stmt
    (az/block
      (exit))))
