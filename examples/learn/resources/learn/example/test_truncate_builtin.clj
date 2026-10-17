(ns learn.example.test-truncate-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest integer-truncation
  (let [a (u16 0xabcd)
        b (u8 (truncate a))]
    (k/try (testing/expectEqual 0xcd b))))

(comment
  (integer-truncation))
