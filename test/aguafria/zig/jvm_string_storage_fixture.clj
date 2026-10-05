(ns aguafria.zig.jvm-string-storage-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest string-coercions
  (let [text (k/as "hello" [:slice-const :u8])]
    (k/try (testing/expectEqual 5 (:len text))))
  (let [text (k/as "世界" [:slice-const :u8])]
    (k/try (testing/expectEqual 6 (:len text))))
  (let [text (k/as "" [:slice-const :u8])]
    (k/try (testing/expectEqual 0 (:len text)))))
