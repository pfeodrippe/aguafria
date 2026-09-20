(ns learn.example.test-comptime-invalid-error-code
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-invalid-error-code
  (set! _ (ak/errorFromInt 12345)))

(comment
  ;; Evaluate the comptime declaration above; it runs during native compilation, not at runtime.
  )
