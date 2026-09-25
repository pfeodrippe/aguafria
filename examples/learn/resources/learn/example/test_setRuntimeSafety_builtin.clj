(ns learn.example.test-setRuntimeSafety-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest scope-local-runtime-safety-test
  ;; Safety is normally disabled by ReleaseFast, but this block overrides it.
  (az/block
    (k/setRuntimeSafety true)
    (let [value (k/var 255 :u8)]
      (k/+= value 1)
      ;; A nested scope can override the setting again.
      (az/block
        (k/setRuntimeSafety false)))))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (scope-local-runtime-safety-test))
