(ns learn.examples.idiomatic-basics.switch-continue-equivalent
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest switch-continue-loop-test
  (let [^{:var :i32} state 5]
    (while true
      (az/switch-stmt state
        (case [5]
          (az/block
            (set! state 4)
            (ak/continue)))
        (case [(az/op "..." 2 4)] [value]
          (az/block
            (if (> value 3)
              (az/block
                (set! state 2)
                (ak/continue))
              (when (== value 3)
                (ak/break)))
            (set! state 1)
            (ak/continue)))
        (case [1]
          (ak/return))
        (az/case-else (ak/unreachable))))))
