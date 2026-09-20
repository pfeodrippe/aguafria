(ns learn.example.test-switch-continue-equivalent
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest switch-continue-loop-test
  (let [state (ak/var 5 :i32)]
    (while true
      (az/switch-stmt state
        (case [5]
          (az/block
            (ak/= state 4)
            (ak/continue)))
        (case [(az/op "..." 2 4)] [value]
          (az/block
            (if (> value 3)
              (az/block
                (ak/= state 2)
                (ak/continue))
              (when (== value 3)
                (ak/break)))
            (ak/= state 1)
            (ak/continue)))
        (case [1]
          (ak/return))
        (az/case-else (ak/unreachable))))))

(comment
  (switch-continue-loop-test))
