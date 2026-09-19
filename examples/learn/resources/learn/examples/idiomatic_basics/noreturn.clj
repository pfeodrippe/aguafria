(ns learn.examples.idiomatic-basics.noreturn
  "Converted from test_noreturn.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- use-when :void
  [[condition :bool] [input :u32]]
  (let [value (if condition
                input
                (ak/return))]
    (set! _ value)
    (ak/panic "do something with a")))

(az/deftest noreturn-test
  (use-when false 1))
