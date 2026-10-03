(ns learn.example.test-while-nested-break
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/deftest nested-break
  (a/while-loop {:label outer} true
                (k/while true
                  (a/break-label outer))))

(a/deftest nested-continue
  (let [i (k/var 0 :usize)]
    (a/while-loop {:label outer
                   :continue (a/assign-expr "+=" i 1)}
                  (k/< i 10)
                  (k/while true
                    (k/continue outer)))))

(comment
  (nested-break)
  (nested-continue))
