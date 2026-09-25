(ns learn.example.verbose-cimport-flag
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as k]
            [aguafria.std.Target.Os :as os]
            [aguafria.zig :as az]))

(az/defconst c
  (k/cImport
   (az/block
     (k/cDefine "_NO_CRT_STDIO_INLINE" "1")
     (k/cInclude "stdio.h"))))

(az/defn main :void
  []
  (when (k/== (os/-tag builtin/os) :.netbsd)
    ;; https://github.com/Vexu/arocc/issues/960
    (k/return))
  (k/= :_ c))

(comment
  (main))
