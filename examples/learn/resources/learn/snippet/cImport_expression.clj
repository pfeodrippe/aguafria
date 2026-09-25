(ns learn.snippet.cImport-expression
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defimport builtin "builtin" [mode])

(az/defconst c
  (k/cImport
   (az/block
     (k/cDefine "NDEBUG" (k/== builtin/mode :.ReleaseFast))
     (when something
       (k/cDefine "_GNU_SOURCE" (az/block)))
     (k/cInclude "stdlib.h")
     (when something
       (k/cUndef "_GNU_SOURCE"))
     (k/cInclude "soundio.h"))))
