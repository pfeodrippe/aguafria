(ns learn.example.test-truncate-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-truncation-test
  (let [a (ak/u16 0xabcd)
        b (ak/u8 (ak/truncate a))]
    (try (testing/expectEqual 0xcd b))))

(comment
  (integer-truncation-test))
