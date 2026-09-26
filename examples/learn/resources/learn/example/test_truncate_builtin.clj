(ns learn.example.test-truncate-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-truncation
  (let [a (k/u16 0xabcd)
        b (k/u8 (k/truncate a))]
    (try (testing/expectEqual 0xcd b))))

(comment
  (integer-truncation))
