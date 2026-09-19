(ns learn.example.test-sentinel-mismatch
  (:require [aguafria.zig :as az]))

(az/deftest sentinel-mismatch-test
  (let [^:var bytes (az/array-init [:array _ :u8] [3 2 1 0])
        ^{:var :usize} length 2]
    (set! _ (& length))
    ;; Intentionally panics: bytes[length] is 1, not the promised zero sentinel.
    (let [slice (az/slice-sentinel bytes 0 length 0)]
      (set! _ slice))))
