(ns learn.example.test-comptime-reaching-unreachable
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn- require-condition :void [[condition :bool]]
  (when (ak/! condition)
    (ak/unreachable)))

;; A false assertion reaches unreachable during compile-time evaluation.
(az/defcomptime reject-false-condition
  (require-condition false))

(comment
  ;; Evaluate the comptime declaration above; it runs during native compilation, not at runtime.
  )
