(ns learn.examples.idiomatic-values.intcast-builtin
  "Converted from test_intCast_builtin.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest integer-cast-panic-test
  (let [^{:var :u16} wide 0xabcd] ; runtime-known
    (set! _ (& wide))
    (let [^{:zig/type :u8} narrow (ak/intCast wide)]
      (set! _ narrow))))
