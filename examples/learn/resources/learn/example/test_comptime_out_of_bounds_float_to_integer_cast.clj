(ns learn.example.test-comptime-out-of-bounds-float-to-integer-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-out-of-range-float
  (let [float-value (k/f32 4294967296)
        integer-value (k/i32 (k/intFromFloat float-value))]
    (k/= :_ integer-value)))
