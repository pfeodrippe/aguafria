(ns learn.example.test-while-nested-break
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest nested-break
  (az/while-loop {:label outer} true
                 (k/while true
                   (az/break-label outer))))

(az/deftest nested-continue
  (let [i (k/var 0 :usize)]
    (az/while-loop {:label outer
                    :continue (az/assign-expr "+=" i 1)}
                   (k/< i 10)
                   (k/while true
                     (k/continue outer)))))

(comment
  (nested-break)
  (nested-continue))
