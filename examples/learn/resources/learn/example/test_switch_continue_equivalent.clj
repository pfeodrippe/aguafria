(ns learn.example.test-switch-continue-equivalent
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest switch-continue-equivalent-loop
  (let [sw (k/var 5 :i32)]
    (while true
      (switch sw
        5
        (a/block
         (= sw 4)
         (continue))
        (k/... 2 4)
        (a/with-block :_ [v] (a/block
                              (if (> v 3)
                                (a/block
                                 (= sw 2)
                                 (continue))
                                (when (== v 3)
                                  (break)))
                              (= sw 1)
                              (continue)))
        1
        (return)
        (unreachable)))))

(comment
  (switch-continue-equivalent-loop))
