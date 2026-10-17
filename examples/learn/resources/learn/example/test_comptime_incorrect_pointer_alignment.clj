(ns learn.example.test-comptime-incorrect-pointer-alignment
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-unaligned-address
  (let [ptr (as (ptrFromInt 0x1) [:* {:align 1} :i32])
        aligned (as (alignCast ptr) [:* {:align 4} :i32])]
    (= :_ aligned)))
