(ns learn.example.test-comptime-invalid-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-negative-unsigned-value
  (let [value (k/i32 -1)
        unsigned (k/u32 (k/intCast value))]
    (k/= :_ unsigned)))
