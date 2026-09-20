(ns learn.example.test-comptime-overflow
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

;; Checked addition cannot store 256 in an eight-bit unsigned integer.
(az/defcomptime checked-byte-overflow
  (let [^{:var :u8} byte 255]
    (ak/+= byte 1)))

(comment
  ;; Evaluate the comptime declaration above; it runs during native compilation, not at runtime.
  )
