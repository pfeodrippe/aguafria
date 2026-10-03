(ns learn.example.test-while-else
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- range-has-number :bool
  [[begin :usize] [end :usize] [number :usize]]
  (let [i (k/var begin)]
    (a/while-loop {:continue (a/assign-expr "+=" i 1)
                   :else-expression false}
                  (k/< i end)
                  (if (k/== i number)
                    (k/break true)))))

(a/deftest while-else
  (try (testing/expect (range-has-number 0 10 5)))
  (try (testing/expect (k/! (range-has-number 0 10 15)))))

(comment
  (while-else))
