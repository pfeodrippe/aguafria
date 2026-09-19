(ns learn.examples.idiomatic-safety.comptime-index-out-of-bounds
  "Converted from test_comptime_index_out_of_bounds.zig"
  (:require [aguafria.zig :as az]))

(az/defcomptime reject-sixth-byte
  (let [^{:zig/type [:array 5 :u8]} bytes (deref "hello")
        invalid-byte (az/index bytes 5)]
    (set! _ invalid-byte)))
