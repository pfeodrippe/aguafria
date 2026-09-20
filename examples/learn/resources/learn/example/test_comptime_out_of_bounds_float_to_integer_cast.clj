(ns learn.example.test-comptime-out-of-bounds-float-to-integer-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-out-of-range-float
  (let [^{:zig/type :f32} float-value 4294967296
        ^{:zig/type :i32} integer-value (ak/intFromFloat float-value)]
    (set! _ integer-value)))

(comment
  ;; Evaluate the comptime declaration above; it runs during native compilation, not at runtime.
  )
