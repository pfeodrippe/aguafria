(ns learn.examples.idiomatic-basics.unresolved-comptime-value
  "Converted from test_unresolved_comptime_value.zig"
  (:require [aguafria.zig :as az]))

(az/defn- maximum T
  [[T {:zig/prefix "comptime"} :type] [left T] [right T]]
  (if (> left right) left right))

(az/defn- choose-runtime-type :void [[condition :bool]]
  (let [result (maximum (if condition :f32 :u64) 1234 5678)]
    (set! _ result)))

(az/deftest runtime-type-is-not-comptime-test
  (choose-runtime-type false))
