(ns learn.examples.idiomatic-errors.runtime-invalid-cast
  "Converted from runtime_invalid_cast.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^{:var :i32} signed-value -1]
    (set! _ (ak/& signed-value))
    (let [^{:zig/type :u32} unsigned-value (ak/intCast signed-value)]
      (debug/print "value: {}\n" [unsigned-value]))))
