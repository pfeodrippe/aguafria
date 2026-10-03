(ns learn.example.test-switch-continue
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/deftest switch-continue
  (a/labeled-switch-stmt sw (k/as 5 :i32)
                         (case [5]
                           (k/continue sw 4))

    ;; `continue` can occur multiple times within a single switch prong.
                         (case [(k/... 2 4)] [v]
                               (a/block
                                (if (k/> v 3)
                                  (k/continue sw 2)
                                  (when (k/== v 3)
            ;; `break` can target labeled loops.
                                    (a/break-label sw)))
                                (k/continue sw 1)))

                         (case [1]
                           (k/return))

                         (a/case-else (k/unreachable))))

(comment
  (switch-continue))
