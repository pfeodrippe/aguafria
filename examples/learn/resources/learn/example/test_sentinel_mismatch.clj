(ns learn.example.test-sentinel-mismatch
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest sentinel-mismatch
  (let [array (k/var (az/array [3 2 1 0] :u8))
        ;; Creating a sentinel-terminated slice from the array with a length of 2
        ;; will result in the value `1` occupying the sentinel element position.
        ;; This does not match the indicated sentinel value of `0` and will lead
        ;; to a runtime panic.
        runtime-length (k/var 2 :usize)]
    (k/= :_ (k/& runtime-length))
    (let [slice (az/slice-sentinel array 0 runtime-length 0)]
      (k/= :_ slice))))

(comment
  (sentinel-mismatch))
