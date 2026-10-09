(ns aguafria.native-source-identity
  "Developer comparison: stream source-key bytes without heap allocation."
  (:require [aguafria.keyword :as k]
            [aguafria.std.crypto.hash.sha2 :as sha2]
            [aguafria.zig :as a]))

(a/defconst max-files :u32 1024)
(a/defconst max-bytes :u32 33554432)

(a/defn- source-key :void
  [[source [:slice-const :u8]] [output [:* [:array 32 :u8]]]]
  (let [hash (k/var ((:init sha2/Sha256) {}))
        chunk (k/var k/undefined [:array 4096 :u8])
        used (k/var 0 :usize)]
    ((:update sha2/Sha256) (k/& hash) "[1 2 :bundle-source [:scalar \"")
    (k/for [byte source]
      (let [escaped (k/as (cond
                            (k/== byte 34) 34
                            (k/== byte 92) 92
                            (k/== byte 10) 110
                            (k/== byte 9) 116
                            (k/== byte 13) 114
                            (k/== byte 8) 98
                            (k/== byte 12) 102
                            :else 0) :u8)]
        (when (k/> used 4094)
          ((:update sha2/Sha256) (k/& hash) (a/slice chunk 0 used))
          (k/= used 0))
        (when (k/!= escaped 0)
          (k/= (a/get chunk used) 92)
          (k/+= used 1))
        (k/= (a/get chunk used) (if (k/!= escaped 0) (k/as escaped :u8) byte))
        (k/+= used 1)))
    ((:update sha2/Sha256) (k/& hash) (a/slice chunk 0 used))
    ((:update sha2/Sha256) (k/& hash) "\"]]")
    ((:final sha2/Sha256) (k/& hash) output)))

(a/defn hash_batch :u32 {:attrs #{k/export}}
  [[buffer [:many-const :u8]] [buffer-length :u32]
   [offsets [:many-const :u32]] [lengths [:many-const :u32]]
   [file-count :u32] [output [:many [:array 32 :u8]]]]
  (when (or (k/> file-count max-files) (k/> buffer-length max-bytes))
    (k/return 1))
  (k/for [file (a/range 0 file-count)]
    (let [offset (a/get offsets file)
          length (a/get lengths file)
          end (k/+ (k/u64 offset) (k/u64 length))]
      (when (k/> end buffer-length) (k/return 2))
      (source-key (a/slice (k/+ buffer offset) 0 length)
                  (k/& (a/get output file)))))
  0)
