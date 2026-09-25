(ns learn.example.test-comptime-invalid-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-negative-unsigned-value
  (let [signed-value (k/i32 -1)
        unsigned-value (k/u32 (k/intCast signed-value))]
    (k/= :_ unsigned-value)))
