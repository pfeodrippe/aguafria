(ns learn.example.test-while-else
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest while-else-test
  (try (testing/expect (range-has-number 0 10 5)))
  (try (testing/expect (ak/! (range-has-number 0 10 15)))))

(az/defn- range-has-number :bool
  [[begin :usize] [end :usize] [number :usize]]
  (let [^:var i begin]
    (ak/return
      (az/while-loop {:continue (az/assign-expr "+=" i 1)
                      :else-expression false}
        (< i end)
        (if (== i number)
          (ak/break true))))))
