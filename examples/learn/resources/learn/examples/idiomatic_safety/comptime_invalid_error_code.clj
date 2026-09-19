(ns learn.examples.idiomatic-safety.comptime-invalid-error-code
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-invalid-error-code
  (set! _ (ak/errorFromInt 12345)))
