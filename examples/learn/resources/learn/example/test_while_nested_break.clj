(ns learn.example.test-while-nested-break
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/deftest nested-break-test
  (az/while-loop {:label outer} true
    (while true
      (az/break-label outer))))

(az/deftest nested-continue-test
  (let [^:var i (ak/usize 0)]
    (az/while-loop {:label outer
                    :continue (az/assign-expr "+=" i 1)}
      (< i 10)
      (while true
        (ak/continue outer)))))

(comment
  (nested-break-test)
  (nested-continue-test))
