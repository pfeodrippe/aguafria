(ns learn.example.test-int-to-float-coercion
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest implicit-integer-to-float
  (let [int (k/var 123 :u8)]
    (k/= :_ (k/& int))
    (let [float (k/f32 int)
          int-from-float (k/u8 (k/intFromFloat float))]
      (try (testing/expectEqual int int-from-float)))))

(comment
  (implicit-integer-to-float))
