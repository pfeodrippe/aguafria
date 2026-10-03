(ns learn.example.test-comptime-shlExact-overflow
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defcomptime reject-lost-high-bits
  (let [x (k/shlExact (k/u8 2r01010101) 2)]
    (k/= :_ x)))
