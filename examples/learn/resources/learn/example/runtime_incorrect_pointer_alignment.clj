(ns learn.example.runtime-incorrect-pointer-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]))

(az/defn- foo :u32 [[bytes [:slice :u8]]]
  (let [four-bytes (az/slice bytes 1 5)
        aligned-bytes (k/as (k/alignCast four-bytes)
                             (az/type [:pointer {:size :slice :align 4} :u8]))
        words (mem/bytesAsSlice :u32 aligned-bytes)]
    (az/index words 0)))

(az/defn main [:error-union :void] []
  (let [words
        (k/var (az/array-init [0x11111111 0x11111111] [:array :_ :u32]) nil {:zig/align 4})
        bytes (mem/sliceAsBytes (az/slice words 0))]
    (when (k/!= (foo bytes) 0x11111111)
      (k/return (az/error-value :Wrong)))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
