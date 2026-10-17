(ns learn.example.test-simple-union
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defunion Payload
  [[:int :i64]
   [:float :f64]
   [:boolean :bool]])

(a/deftest simple-union
  (let [payload (k/var (Payload {:int 1234}))]
    (k/try (testing/expectEqual 1234 (:int payload)))
    (= payload (Payload {:float 12.34}))
    (k/try (testing/expectEqual 12.34 (:float payload)))))

(comment
  (simple-union))
