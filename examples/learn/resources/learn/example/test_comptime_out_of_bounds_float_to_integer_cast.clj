(ns learn.example.test-comptime-out-of-bounds-float-to-integer-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-out-of-range-float
  (let [float-value (ak/f32 4294967296)
        integer-value (ak/i32 (ak/intFromFloat float-value))]
    (ak/= :_ integer-value)))
