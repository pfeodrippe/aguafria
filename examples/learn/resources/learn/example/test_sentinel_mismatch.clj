(ns learn.example.test-sentinel-mismatch
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest sentinel-mismatch-test
  (let [^:var bytes (az/array-init [:array _ :u8] [3 2 1 0])
        ^:var length (ak/usize 2)]
    (set! _ (& length))
    ;; Intentionally panics: bytes[length] is 1, not the promised zero sentinel.
    (let [slice (az/slice-sentinel bytes 0 length 0)]
      (set! _ slice))))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (sentinel-mismatch-test))
