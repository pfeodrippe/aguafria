(ns learn.example.test-truncate-builtin
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-truncation-test
  (let [^{:zig/type :u16} a 0xabcd
        ^{:zig/type :u8} b (ak/truncate a)]
    (try (testing/expectEqual 0xcd b))))
