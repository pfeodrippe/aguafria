(ns learn.example.test-switch-continue
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest switch-continue-test
  (az/labeled-switch-stmt sw (k/as 5 :i32)
                          (case [5]
                            (k/continue sw 4))

    ;; `continue` can occur multiple times within a single switch prong.
                          (case [(az/op "..." 2 4)] [value]
                            (az/block
                              (if (k/> value 3)
                                (k/continue sw 2)
                                (when (k/== value 3)
            ;; `break` can target labeled loops.
                                  (az/break-label sw)))
                              (k/continue sw 1)))

                          (case [1]
                            (k/return))

                          (az/case-else (k/unreachable))))

(comment
  (switch-continue-test))
