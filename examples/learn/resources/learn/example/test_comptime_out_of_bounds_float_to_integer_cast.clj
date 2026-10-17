(ns learn.example.test-comptime-out-of-bounds-float-to-integer-cast
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-out-of-range-float
  (let [float (f32 4294967296)
        int (i32 (intFromFloat float))]
    (= :_ int)))
