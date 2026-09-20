(ns learn.example.test-sentinel-mismatch
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest sentinel-mismatch-test
  (let [bytes (ak/var (az/array-init [3 2 1 0] [:array :_ :u8]))
        length (ak/var 2 :usize)]
    (ak/= :_ (& length))
    ;; Intentionally panics: bytes[length] is 1, not the promised zero sentinel.
    (let [slice (az/slice-sentinel bytes 0 length 0)]
      (ak/= :_ slice))))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (sentinel-mismatch-test))
