(ns learn.examples.idiomatic-safety.comptime-index-out-of-bounds
  (:require [aguafria.zig :as az]))

(az/defcomptime reject-sixth-byte
  (let [^{:zig/type [:array 5 :u8]} bytes (deref "hello")
        invalid-byte (az/index bytes 5)]
    (set! _ invalid-byte)))
