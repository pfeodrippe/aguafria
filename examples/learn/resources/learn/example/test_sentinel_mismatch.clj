(ns learn.example.test-sentinel-mismatch
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest sentinel-mismatch-test
  (let [bytes (k/var (az/array [3 2 1 0] :u8))
        length (k/var 2 :usize)]
    (k/= :_ (k/& length))
    ;; Intentionally panics: bytes[length] is 1, not the promised zero sentinel.
    (let [slice (az/slice-sentinel bytes 0 length 0)]
      (k/= :_ slice))))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (sentinel-mismatch-test))
