(ns learn.examples.idiomatic-types.anonymous-struct
  "Converted from test_anonymous_struct.zig"
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn check-fields [:error-union :void]
  [[value :anytype]]
  (try (testing/expectEqual 1234 (az/field value :int)))
  (try (testing/expectEqual 12.34 (az/field value :float)))
  (try (testing/expect (az/field value :b)))
  (try (testing/expectEqual \h (az/index (az/field value :s) 0)))
  (try (testing/expectEqual \i (az/index (az/field value :s) 1))))

(az/deftest fully-anonymous-struct-test
  (try (check-fields {:int (ak/as (az/type :u32) 1234)
                      :float (ak/as (az/type :f64) 12.34)
                      :b true
                      :s "hi"})))
