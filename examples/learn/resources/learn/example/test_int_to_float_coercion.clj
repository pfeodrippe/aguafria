(ns learn.example.test-int-to-float-coercion
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-to-float-test
  (let [^:var integer (ak/u8 123)]
    (set! _ (& integer))
    ;; Every u8 value is exactly representable by f32.
    (let [floating (ak/f32 integer)
          restored (ak/u8 (ak/intFromFloat floating))]
      (try (testing/expectEqual integer restored)))))

(comment
  (integer-to-float-test))
