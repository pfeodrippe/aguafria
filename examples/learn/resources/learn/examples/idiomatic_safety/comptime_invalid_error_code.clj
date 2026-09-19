(ns learn.examples.idiomatic-safety.comptime-invalid-error-code
  "Converted from test_comptime_invalid_error_code.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-invalid-error-code
  (set! _ (ak/errorFromInt 12345)))
