(ns learn.example.test-int-to-float-coercion
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-to-float-test
  (let [integer (k/var 123 :u8)]
    (k/= :_ (k/& integer))
    ;; Every u8 value is exactly representable by f32.
    (let [floating (k/f32 integer)
          restored (k/u8 (k/intFromFloat floating))]
      (try (testing/expectEqual integer restored)))))

(comment
  (integer-to-float-test))
