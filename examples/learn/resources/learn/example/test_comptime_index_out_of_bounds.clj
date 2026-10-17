(ns learn.example.test-comptime-index-out-of-bounds
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-sixth-byte
  (let [array (as (a/deref "hello") [:array 5 :u8])
        garbage (a/get array 5)]
    (= :_ garbage)))
