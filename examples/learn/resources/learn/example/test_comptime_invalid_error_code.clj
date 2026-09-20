(ns learn.example.test-comptime-invalid-error-code
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-invalid-error-code
  (ak/= :_ (ak/errorFromInt 12345)))
