(ns learn.example.test-packed-union-equality
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest packed-union-equality
  (let [U (a/union {:layout :packed}
                   [[:a :u4]
                    [:b :i4]])
        x (U {:a 3})
        y (U {:b 3})]
    (try (testing/expectEqual x y))))

(comment
  (packed-union-equality))
