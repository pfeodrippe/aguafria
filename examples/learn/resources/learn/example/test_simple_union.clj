(ns learn.example.test-simple-union
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst Payload
  (az/union
   [[:int :i64]
    [:float :f64]
    [:boolean :bool]]))

(az/deftest simple-union
  (let [payload (k/var (Payload {:int 1234}))]
    (try (testing/expectEqual 1234 (:int payload)))
    (k/= payload (Payload {:float 12.34}))
    (try (testing/expectEqual 12.34 (:float payload)))))

(comment
  (simple-union))
