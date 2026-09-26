(ns learn.example.test-comptime-index-out-of-bounds
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-sixth-byte
  (let [bytes (k/as (deref "hello") [:array 5 :u8])
        invalid-byte (az/get bytes 5)]
    (k/= :_ invalid-byte)))
