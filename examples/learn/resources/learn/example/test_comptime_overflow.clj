(ns learn.example.test-comptime-overflow
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

;; Checked addition cannot store 256 in an eight-bit unsigned integer.
(az/defcomptime checked-byte-overflow
  (let [byte (k/var 255 :u8)]
    (k/+= byte 1)))
