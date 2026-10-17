(ns learn.example.test-comptime-invalid-cast-truncate
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-too-large-byte
  (let [spartan-count (u16 300)
        byte (u8 (intCast spartan-count))]
    (= :_ byte)))
