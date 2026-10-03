(ns learn.example.test-comptime-index-out-of-bounds
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defcomptime reject-sixth-byte
  (let [array (k/as (deref "hello") [:array 5 :u8])
        garbage (a/get array 5)]
    (k/= :_ garbage)))
