(ns learn.example.test-int-to-float-coercion
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest implicit-integer-to-float
  (let [int (k/var 123 :u8)]
    (= :_ (k/& int))
    (let [float (f32 int)
          int-from-float (u8 (intFromFloat float))]
      (k/try (testing/expectEqual int int-from-float)))))

(comment
  (implicit-integer-to-float))
