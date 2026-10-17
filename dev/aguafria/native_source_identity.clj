(ns aguafria.native-source-identity
  "Developer comparison: stream source-key bytes without heap allocation."
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.crypto.hash.sha2 :as sha2]
            [aguafria.zig :as a]))

(a/defconst max-files :u32 1024)
(a/defconst max-bytes :u32 33554432)

(a/defn- source-key :void
  [[source [:slice-const :u8]] [output [:* [:array 32 :u8]]]]
  (let [hash (k/var ((:init sha2/Sha256) {}))
        chunk (k/var undefined [:array 4096 :u8])
        used (k/var 0 :usize)]
    ((:update sha2/Sha256) (k/& hash) "[1 2 :bundle-source [:scalar \"")
    (for [byte source]
      (let [escaped (as (cond
                          (== byte 34) 34
                          (== byte 92) 92
                          (== byte 10) 110
                          (== byte 9) 116
                          (== byte 13) 114
                          (== byte 8) 98
                          (== byte 12) 102
                          :else 0) :u8)]
        (when (> used 4094)
          ((:update sha2/Sha256) (k/& hash) (a/slice chunk 0 used))
          (= used 0))
        (when (!= escaped 0)
          (= (a/get chunk used) 92)
          (+= used 1))
        (= (a/get chunk used) (if (!= escaped 0) (as escaped :u8) byte))
        (+= used 1)))
    ((:update sha2/Sha256) (k/& hash) (a/slice chunk 0 used))
    ((:update sha2/Sha256) (k/& hash) "\"]]")
    ((:final sha2/Sha256) (k/& hash) output)))

(a/defn hash_batch :u32 {:attrs #{export}}
  [[buffer [:many-const :u8]] [buffer-length :u32]
   [offsets [:many-const :u32]] [lengths [:many-const :u32]]
   [file-count :u32] [output [:many [:array 32 :u8]]]]
  (when (or (> file-count max-files) (> buffer-length max-bytes))
    (return 1))
  (for [file (a/range 0 file-count)]
    (let [offset (a/get offsets file)
          length (a/get lengths file)
          end (+ (u64 offset) (u64 length))]
      (when (> end buffer-length) (return 2))
      (source-key (a/slice (+ buffer offset) 0 length)
                  (k/& (a/get output file)))))
  0)
