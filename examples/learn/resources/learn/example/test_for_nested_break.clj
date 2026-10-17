(ns learn.example.test-for-nested-break
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest nested-break
  (let [count (k/var 0 :usize)]
    (a/for-loop {:label outer} [_ (a/range 1 6)]
                (for [_ (a/range 1 6)]
                  (+= count 1)
                  (a/break-label outer)))
    (k/try (testing/expectEqual 1 count))))

(a/deftest nested-continue
  (let [count (k/var 0 :usize)]
    (a/for-loop {:label outer} [_ (a/range 1 9)]
                (for [_ (a/range 1 6)]
                  (+= count 1)
                  (continue outer)))
    (k/try (testing/expectEqual 8 count))))

(comment
  (nested-break)
  (nested-continue))
