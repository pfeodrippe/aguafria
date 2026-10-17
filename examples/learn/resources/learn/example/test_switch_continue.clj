(ns learn.example.test-switch-continue
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest switch-continue
  (switch [sw (as 5 :i32)]
    5
    (continue sw 4)

    ;; `continue` can occur multiple times within a single switch prong.
    (k/... 2 4)
    (a/with-block :_ [v] (a/block
                          (if (> v 3)
                            (continue sw 2)
                            (when (== v 3)
                              ;; `break` can target labeled loops.
                              (a/break-label sw)))
                          (continue sw 1)))

    1
    (return)

    (unreachable)))

(comment
  (switch-continue))
