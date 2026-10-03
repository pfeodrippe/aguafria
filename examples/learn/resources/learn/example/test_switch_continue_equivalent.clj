(ns learn.example.test-switch-continue-equivalent
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/deftest switch-continue-equivalent-loop
  (let [sw (k/var 5 :i32)]
    (k/while true
      (a/switch-stmt sw
                     (case [5]
                       (a/block
                        (k/= sw 4)
                        (k/continue)))
                     (case [(k/... 2 4)] [v]
                           (a/block
                            (if (k/> v 3)
                              (a/block
                               (k/= sw 2)
                               (k/continue))
                              (when (k/== v 3)
                                (k/break)))
                            (k/= sw 1)
                            (k/continue)))
                     (case [1]
                       (k/return))
                     (a/case-else (k/unreachable))))))

(comment
  (switch-continue-equivalent-loop))
