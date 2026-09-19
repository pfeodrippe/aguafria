(ns learn.examples.idiomatic-values.no-op-casts
  "Converted from test_no_op_casts.zig"
  (:require [aguafria.zig :as az]))

(az/deftest const-qualification-test
  (let [^{:var :i32} value 1
        ^{:zig/type [:* :i32]} pointer (& value)]
    (accept-const-pointer pointer)))

(az/defn- accept-const-pointer :void
  [[_ [:*const :i32]]])
