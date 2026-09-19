(ns learn.examples.idiomatic-basics.while
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest while-basic-test
  (let [^{:var :usize} i 0]
    (while (< i 10)
      (ak/+= i 1))
    (try (testing/expectEqual 10 i))))
