(ns learn.example.test-pointer-to-non-byte-aligned-field
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct BitField {:layout :packed}
              [[:a :u3] [:b :u3] [:c :u2]])

(az/defvar foo (BitField {:a 1 :b 2 :c 3}))

(az/deftest pointer-to-non-byte-aligned-field
  (let [ptr (k/& (:b foo))]
    (try (testing/expectEqual 2 @ptr))))

(comment
  (pointer-to-non-byte-aligned-field))
