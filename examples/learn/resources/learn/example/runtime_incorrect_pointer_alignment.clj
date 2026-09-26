(ns learn.example.runtime-incorrect-pointer-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as az]))

(az/defn- foo :u32 [[bytes [:slice :u8]]]
  (let [slice4 (az/slice bytes 1 5)
        int-slice (mem/bytesAsSlice :u32
                                    (k/as (k/alignCast slice4)
                                          (az/type [:pointer {:size :slice :align 4} :u8])))]
    (az/get int-slice 0)))

(az/defn main [:error-union :void] []
  (let [array
        (k/var (az/array [0x11111111 0x11111111] :u32) nil {:zig/align 4})
        bytes (mem/sliceAsBytes (az/slice array 0))]
    (when (k/!= (foo bytes) 0x11111111)
      (k/return (az/error-value :Wrong)))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
