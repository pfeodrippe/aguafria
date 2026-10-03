(ns learn.example.runtime-incorrect-pointer-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]))

(a/defn- foo :u32 [[bytes [:slice :u8]]]
  (let [slice4 (a/slice bytes 1 5)
        int-slice (mem/bytesAsSlice :u32
                                    (k/as (k/alignCast slice4)
                                          [:* {:size :slice :align 4} :u8]))]
    (a/get int-slice 0)))

(a/defn main [:error-union :void] []
  (let [array
        (k/var (a/array [0x11111111 0x11111111] :u32) nil {:align 4})
        bytes (mem/sliceAsBytes (a/slice array 0))]
    (when (k/!= (foo bytes) 0x11111111)
      (k/return (a/error-value :Wrong)))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
