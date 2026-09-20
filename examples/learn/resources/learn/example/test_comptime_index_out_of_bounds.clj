(ns learn.example.test-comptime-index-out-of-bounds
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-sixth-byte
  (let [bytes (ak/as (deref "hello") [:array 5 :u8])
        invalid-byte (az/index bytes 5)]
    (ak/= :_ invalid-byte)))
