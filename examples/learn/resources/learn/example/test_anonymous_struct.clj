(ns learn.example.test-anonymous-struct
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn check [:error-union :void]
  [[value :anytype]]
  (try (testing/expectEqual 1234 (:int value)))
  (try (testing/expectEqual 12.34 (:float value)))
  (try (testing/expect (:b value)))
  (try (testing/expectEqual \h (az/get-in value [:s 0])))
  (try (testing/expectEqual \i (az/get-in value [:s 1]))))

(az/deftest fully-anonymous-struct-test
  (try (check {:int (k/as 1234 (az/type :u32))
                      :float (k/as 12.34 (az/type :f64))
                      :b true
                      :s "hi"})))

(comment
  (fully-anonymous-struct-test))
