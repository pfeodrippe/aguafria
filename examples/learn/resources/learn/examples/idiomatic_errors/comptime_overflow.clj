(ns learn.examples.idiomatic-errors.comptime-overflow
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; Checked addition cannot store 256 in an eight-bit unsigned integer.
(az/defcomptime checked-byte-overflow
  (let [^{:var :u8} byte 255]
    (ak/+= byte 1)))
