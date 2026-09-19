(ns learn.examples.idiomatic-library.set-runtime-safety
  "Converted from test_setRuntimeSafety_builtin.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest scope-local-runtime-safety-test
  ;; Safety is normally disabled by ReleaseFast, but this block overrides it.
  (az/block
    (ak/setRuntimeSafety true)
    (let [^{:var :u8} value 255]
      (ak/+= value 1)
      ;; A nested scope can override the setting again.
      (az/block
        (ak/setRuntimeSafety false)))))
