(ns learn.example.verbose-cimport-flag
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst c
  (ak/cImport
   (az/block
     (ak/cDefine "_NO_CRT_STDIO_INLINE" "1")
     (ak/cInclude "stdio.h"))))

(az/defconst builtin (ak/import "builtin"))

(az/defn main :void
  []
  (when (== (az/field (az/field builtin :os) :tag) :.netbsd)
    ;; https://github.com/Vexu/arocc/issues/960
    (ak/return))
  (set! _ c))

(comment
  (main))
