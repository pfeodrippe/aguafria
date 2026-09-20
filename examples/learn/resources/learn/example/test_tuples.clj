(ns learn.example.test-tuples
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest tuple-test
  (let [values (az/op "++"
                      [(ak/as :u32 1234) (ak/as :f64 12.34) true "hi"]
                      (az/op "**" [false] 2))]
    (try (testing/expectEqual 1234 (az/index values 0)))
    (try (testing/expectEqual false (az/index values 4)))
    (az/inline-for [[value values] [index (az/op ".." 0)]]
      (when (ak/!= index 2)
        (ak/continue))
      (try (testing/expect value)))
    (try (testing/expectEqual 6 (az/field values :len)))
    (try (testing/expectEqual \h (az/index (az/field values :3) 0)))))

(comment
  (tuple-test))
