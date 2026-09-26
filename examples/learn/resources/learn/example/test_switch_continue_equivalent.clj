(ns learn.example.test-switch-continue-equivalent
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest switch-continue-loop-test
  (let [state (k/var 5 :i32)]
    (k/while true
      (az/switch-stmt state
        (case [5]
          (az/block
            (k/= state 4)
            (k/continue)))
        (case [(k/... 2 4)] [value]
          (az/block
            (if (k/> value 3)
              (az/block
                (k/= state 2)
                (k/continue))
              (when (k/== value 3)
                (k/break)))
            (k/= state 1)
            (k/continue)))
        (case [1]
          (k/return))
        (az/case-else (k/unreachable))))))

(comment
  (switch-continue-loop-test))
