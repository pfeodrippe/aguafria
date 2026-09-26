(ns learn.example.test-packed-union-equality
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest packed-union-equality
  (let [U (az/union {:layout :packed}
                    [[:a :u4]
                     [:b :i4]])
        x (U {:a 3})
        y (U {:b 3})]
    (try (testing/expectEqual x y))))

(comment
  (packed-union-equality))
