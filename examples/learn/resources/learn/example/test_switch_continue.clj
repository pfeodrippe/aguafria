(ns learn.example.test-switch-continue
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/deftest switch-continue
  (k/switch [sw (k/as 5 :i32)]
    5
    (k/continue sw 4)

    ;; `continue` can occur multiple times within a single switch prong.
    (k/... 2 4)
    (a/with-block :_ [v] (a/block
                          (if (k/> v 3)
                            (k/continue sw 2)
                            (when (k/== v 3)
                              ;; `break` can target labeled loops.
                              (a/break-label sw)))
                          (k/continue sw 1)))

    1
    (k/return)

    (k/unreachable)))

(comment
  (switch-continue))
