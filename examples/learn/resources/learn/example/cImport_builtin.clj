(ns learn.example.cImport-builtin
  (:require [aguafria.builtin :as builtin]
            [aguafria.keyword :as ak]
            [aguafria.std.Target.Os :as os]
            [aguafria.zig :as az]))

(az/defconst c
  (ak/cImport
   (az/block
      ;; See https://github.com/ziglang/zig/issues/515
     (ak/cDefine "_NO_CRT_STDIO_INLINE" "1")
     (ak/cInclude "stdio.h"))))

(az/defn main :void
  []
  (when (ak/== (os/-tag builtin/os) :.netbsd)
    ;; https://github.com/Vexu/arocc/issues/960
    (ak/return))
  (ak/= :_ ((az/field c :printf) "hello\n")))

(comment
  (main))
