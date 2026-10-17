(ns aguafria.zig.jvm-string-storage-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest string-coercions
  (let [text (as "hello" [:slice-const :u8])]
    (k/try (testing/expectEqual 5 (:len text))))
  (let [text (as "世界" [:slice-const :u8])]
    (k/try (testing/expectEqual 6 (:len text))))
  (let [text (as "" [:slice-const :u8])]
    (k/try (testing/expectEqual 0 (:len text)))))
