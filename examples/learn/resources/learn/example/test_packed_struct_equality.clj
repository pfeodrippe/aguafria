(ns learn.example.test-packed-struct-equality
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest packed-struct-equality-test
  (let [Nibbles (az/struct {:layout :packed}
                  [[:a :u4]
                   [:b :u4]])
        forward (Nibbles {:a 1 :b 2})
        reversed (Nibbles {:b 2 :a 1})]
    ;; Literal field order does not affect the packed representation.
    (try (testing/expectEqual forward reversed))))

(comment
  (packed-struct-equality-test))
