(ns learn.example.test-while-else
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- range-has-number :bool
  [[begin :usize] [end :usize] [number :usize]]
  (let [i (k/var begin)]
    (a/while-loop {:continue (a/assign-expr "+=" i 1)
                   :else-expression false}
                  (< i end)
                  (if (== i number)
                    (break true)))))

(a/deftest while-else
  (k/try (testing/expect (range-has-number 0 10 5)))
  (k/try (testing/expect (! (range-has-number 0 10 15)))))

(comment
  (while-else))
