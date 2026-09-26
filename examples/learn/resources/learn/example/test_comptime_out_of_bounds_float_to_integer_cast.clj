(ns learn.example.test-comptime-out-of-bounds-float-to-integer-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-out-of-range-float
  (let [float (k/f32 4294967296)
        int (k/i32 (k/intFromFloat float))]
    (k/= :_ int)))
