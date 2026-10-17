(ns learn.example.test-while-nested-break
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest nested-break
  (a/while-loop {:label outer} true
                (while true
                  (a/break-label outer))))

(a/deftest nested-continue
  (let [i (k/var 0 :usize)]
    (a/while-loop {:label outer
                   :continue (a/assign-expr "+=" i 1)}
                  (< i 10)
                  (while true
                    (continue outer)))))

(comment
  (nested-break)
  (nested-continue))
