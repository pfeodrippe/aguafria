(ns learn.example.test-comptime-overflow
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime checked-byte-overflow
  (let [byte (k/var 255 :u8)]
    (+= byte 1)))
