(ns learn.example.test-tuples
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest tuple-test
  (let [values (az/op "++"
                      [(k/as 1234 :u32) (k/as 12.34 :f64) true "hi"]
                      (az/op "**" [false] 2))]
    (try (testing/expectEqual 1234 (az/index values 0)))
    (try (testing/expectEqual false (az/index values 4)))
    (az/inline-for [[value values] [index (az/op ".." 0)]]
      (when (k/!= index 2)
        (k/continue))
      (try (testing/expect value)))
    (try (testing/expectEqual 6 (az/field values :len)))
    (try (testing/expectEqual \h (az/index (az/field values :3) 0)))))

(comment
  (tuple-test))
