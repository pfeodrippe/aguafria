(ns learn.example.test-while-else
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- range-has-number :bool
  [[begin :usize] [end :usize] [number :usize]]
  (let [i (k/var begin)]
    (az/while-loop {:continue (az/assign-expr "+=" i 1)
                    :else-expression false}
      (k/< i end)
      (if (k/== i number)
        (k/break true)))))

(az/deftest while-else-test
  (try (testing/expect (range-has-number 0 10 5)))
  (try (testing/expect (k/! (range-has-number 0 10 15)))))

(comment
  (while-else-test))
