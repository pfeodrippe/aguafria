(ns learn.example.test-anonymous-struct
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn check [:error-union :void]
  [[{:keys [int float b s]} :anytype]]
  (k/try (testing/expectEqual 1234 int))
  (k/try (testing/expectEqual 12.34 float))
  (k/try (testing/expect b))
  (k/try (testing/expectEqual \h (a/get s 0)))
  (k/try (testing/expectEqual \i (a/get s 1))))

(a/deftest fully-anonymous-struct
  (k/try (check {:int (as 1234 :u32)
                 :float (as 12.34 :f64)
                 :b true
                 :s "hi"})))

(comment
  (fully-anonymous-struct))
