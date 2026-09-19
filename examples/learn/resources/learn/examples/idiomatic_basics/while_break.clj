(ns learn.examples.idiomatic-basics.while-break
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest while-break-test
  (let [^{:var :usize} i 0]
    (while true
      (if (== i 10)
        (ak/break))
      (ak/+= i 1))
    (try (testing/expectEqual 10 i))))
