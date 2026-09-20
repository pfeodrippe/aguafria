(ns learn.example.test-switch-continue
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest switch-continue-test
  (az/labeled-switch-stmt sw (ak/as :i32 5)
    (case [5]
      (ak/continue sw 4))

    ;; `continue` can occur multiple times within a single switch prong.
    (case [(az/op "..." 2 4)] [value]
      (az/block
        (if (> value 3)
          (ak/continue sw 2)
          (when (== value 3)
            ;; `break` can target labeled loops.
            (az/break-label sw)))
        (ak/continue sw 1)))

    (case [1]
      (ak/return))

    (az/case-else (ak/unreachable))))

(comment
  (switch-continue-test))
