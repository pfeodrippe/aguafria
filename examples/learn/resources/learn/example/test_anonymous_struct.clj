(ns learn.example.test-anonymous-struct
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn check [:error-union :void]
  [[value :anytype]]
  (try (testing/expectEqual 1234 (az/field value :int)))
  (try (testing/expectEqual 12.34 (az/field value :float)))
  (try (testing/expect (az/field value :b)))
  (try (testing/expectEqual \h (az/index (az/field value :s) 0)))
  (try (testing/expectEqual \i (az/index (az/field value :s) 1))))

(az/deftest fully-anonymous-struct-test
  (try (check {:int (k/as 1234 (az/type :u32))
                      :float (k/as 12.34 (az/type :f64))
                      :b true
                      :s "hi"})))

(comment
  (fully-anonymous-struct-test))
