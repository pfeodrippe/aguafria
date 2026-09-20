(ns learn.example.test-comptime-index-out-of-bounds
  (:require [aguafria.zig :as az]))

(az/defcomptime reject-sixth-byte
  (let [^{:zig/type [:array 5 :u8]} bytes (deref "hello")
        invalid-byte (az/index bytes 5)]
    (set! _ invalid-byte)))

(comment
  ;; Evaluate the comptime declaration above; it runs during native compilation, not at runtime.
  )
