(ns aguafria.zig.discovery-vector-shift-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn shift [:vector 16 :u8]
  [[bytes [:vector 16 :u8]] [counts [:vector 16 :u3]]]
  (k/>> bytes counts))

(a/deftest never-run
  (let [bytes (a/vector [16 32 48 64 80 96 112 128 144 160 176 192 208 224 240 255] :u8)
        counts (k/as (k/splat 4) [:vector 16 :u3])]
    (k/= :_ (shift bytes counts)))
  (k/unreachable))
