(ns learn.example.test-comptime-invalid-cast
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-negative-unsigned-value
  (let [value (i32 -1)
        unsigned (u32 (intCast value))]
    (= :_ unsigned)))
