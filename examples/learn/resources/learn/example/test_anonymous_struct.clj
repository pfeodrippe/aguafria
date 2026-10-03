(ns learn.example.test-anonymous-struct
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn check [:error-union :void]
  [[{:keys [int float b s]} :anytype]]
  (try (testing/expectEqual 1234 int))
  (try (testing/expectEqual 12.34 float))
  (try (testing/expect b))
  (try (testing/expectEqual \h (a/get s 0)))
  (try (testing/expectEqual \i (a/get s 1))))

(a/deftest fully-anonymous-struct
  (try (check {:int (k/as 1234 :u32)
               :float (k/as 12.34 :f64)
               :b true
               :s "hi"})))

(comment
  (fully-anonymous-struct))
