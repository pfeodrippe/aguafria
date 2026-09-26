(ns learn.example.test-comptime-invalid-cast-truncate
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-too-large-byte
  (let [spartan-count (k/u16 300)
        byte (k/u8 (k/intCast spartan-count))]
    (k/= :_ byte)))
