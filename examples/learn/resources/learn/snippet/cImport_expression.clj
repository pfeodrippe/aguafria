(ns learn.snippet.cImport-expression
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defimport builtin "builtin" [mode])

(az/defconst c
  (ak/cImport
    (az/block
      (ak/cDefine "NDEBUG" (== builtin/mode :.ReleaseFast))
      (when something
        (ak/cDefine "_GNU_SOURCE" (az/block)))
      (ak/cInclude "stdlib.h")
      (when something
        (ak/cUndef "_GNU_SOURCE"))
      (ak/cInclude "soundio.h"))))

(comment
  ;; Contextual excerpt: evaluate the declarations above with the surrounding definitions.
  )
