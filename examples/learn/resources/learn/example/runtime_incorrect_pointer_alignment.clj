(ns learn.example.runtime-incorrect-pointer-alignment
  (:require [aguafria.keyword :as ak]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]))

(az/defn- read-misaligned-word :u32 [[bytes [:slice :u8]]]
  (let [four-bytes (az/slice bytes 1 5)
        aligned-bytes (ak/as (az/type [:pointer {:size :slice :align 4} :u8])
                            (ak/alignCast four-bytes))
        words (mem/bytesAsSlice :u32 aligned-bytes)]
    (az/index words 0)))

(az/defn main [:error-union :void] []
  (let [^{:var true :zig/align 4} words
        (az/array-init [:array _ :u32] [0x11111111 0x11111111])
        bytes (mem/sliceAsBytes (az/slice words 0))]
    (when (ak/!= (read-misaligned-word bytes) 0x11111111)
      (ak/return (az/error-value :Wrong)))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
