(ns learn.example.test-comptime-invalid-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-negative-unsigned-value
  (let [signed-value (ak/i32 -1)
        unsigned-value (ak/u32 (ak/intCast signed-value))]
    (ak/= :_ unsigned-value)))
