(ns learn.example.test-for-nested-break
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest nested-break
  (let [count (k/var 0 :usize)]
    (a/for-loop {:label outer} [_ (a/range 1 6)]
                (k/for [_ (a/range 1 6)]
                  (k/+= count 1)
                  (a/break-label outer)))
    (try (testing/expectEqual 1 count))))

(a/deftest nested-continue
  (let [count (k/var 0 :usize)]
    (a/for-loop {:label outer} [_ (a/range 1 9)]
                (k/for [_ (a/range 1 6)]
                  (k/+= count 1)
                  (k/continue outer)))
    (try (testing/expectEqual 8 count))))

(comment
  (nested-break)
  (nested-continue))
