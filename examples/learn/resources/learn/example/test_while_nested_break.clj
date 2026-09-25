(ns learn.example.test-while-nested-break
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest nested-break-test
  (az/while-loop {:label outer} true
    (k/while true
      (az/break-label outer))))

(az/deftest nested-continue-test
  (let [i (k/var 0 :usize)]
    (az/while-loop {:label outer
                    :continue (az/assign-expr "+=" i 1)}
      (k/< i 10)
      (k/while true
        (k/continue outer)))))

(comment
  (nested-break-test)
  (nested-continue-test))
