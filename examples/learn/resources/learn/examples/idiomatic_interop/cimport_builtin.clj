(ns learn.examples.idiomatic-interop.cimport-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst c
  (ak/cImport
    (az/block
      ;; See https://github.com/ziglang/zig/issues/515
      (ak/cDefine "_NO_CRT_STDIO_INLINE" "1")
      (ak/cInclude "stdio.h"))))

(az/defconst builtin (ak/import "builtin"))

(az/defn main :void
  []
  (when (== (az/field (az/field builtin :os) :tag) :.netbsd)
    ;; https://github.com/Vexu/arocc/issues/960
    (ak/return))
  (set! _ ((az/field c :printf) "hello\n")))
