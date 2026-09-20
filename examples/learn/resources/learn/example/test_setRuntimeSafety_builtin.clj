(ns learn.example.test-setRuntimeSafety-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest scope-local-runtime-safety-test
  ;; Safety is normally disabled by ReleaseFast, but this block overrides it.
  (az/block
    (ak/setRuntimeSafety true)
    (let [^:var value (ak/u8 255)]
      (ak/+= value 1)
      ;; A nested scope can override the setting again.
      (az/block
        (ak/setRuntimeSafety false)))))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (scope-local-runtime-safety-test))
