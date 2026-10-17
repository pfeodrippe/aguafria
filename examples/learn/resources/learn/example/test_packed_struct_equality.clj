(ns learn.example.test-packed-struct-equality
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest packed-struct-equality
  (let [S (a/struct {:layout :packed}
                    [[:a :u4]
                     [:b :u4]])
        x (S {:a 1 :b 2})
        y (S {:b 2 :a 1})]
    (k/try (testing/expectEqual x y))))

(comment
  (packed-struct-equality))
