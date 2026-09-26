(ns learn.example.test-comptime-index-out-of-bounds
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-sixth-byte
  (let [array (k/as (deref "hello") [:array 5 :u8])
        garbage (az/get array 5)]
    (k/= :_ garbage)))
