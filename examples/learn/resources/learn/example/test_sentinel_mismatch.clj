(ns learn.example.test-sentinel-mismatch
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest sentinel-mismatch
  (let [array (k/var (a/array [3 2 1 0] :u8))
        ;; Creating a sentinel-terminated slice from the array with a length of 2
        ;; will result in the value `1` occupying the sentinel element position.
        ;; This does not match the indicated sentinel value of `0` and will lead
        ;; to a runtime panic.
        runtime-length (k/var 2 :usize)]
    (= :_ (k/& runtime-length))
    (let [slice (a/slice-sentinel array 0 runtime-length 0)]
      (= :_ slice))))

(comment
  (sentinel-mismatch))
