(ns learn.example.test-setRuntimeSafety-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest setRuntimeSafety
  ;; The builtin applies to the scope that it is called in. So here, integer overflow
  ;; will not be caught in fast and small modes:
  ;; var x: u8 = 255;
  ;; x += 1; // Unchecked Illegal Behavior in fast/small modes.
  (az/block
    ;; However this block has safety enabled, so safety checks happen here,
    ;; even in fast and small modes.
   (k/setRuntimeSafety true)
   (let [x (k/var 255 :u8)]
     (k/+= x 1)
     (az/block
        ;; The value can be overridden at any scope. So here integer overflow
        ;; would not be caught in any build mode.
      (k/setRuntimeSafety false)
        ;; var x: u8 = 255;
        ;; x += 1; // Unchecked Illegal Behavior in all build modes.
      ))))

(comment
  ;; This deliberately panics and can terminate this JVM.
  (setRuntimeSafety))
