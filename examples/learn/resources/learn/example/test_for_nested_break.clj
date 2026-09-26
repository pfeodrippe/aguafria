(ns learn.example.test-for-nested-break
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest nested-break-test
  (let [count (k/var 0 :usize)]
    (az/for-loop {:label outer} [_ (az/range 1 6)]
      (k/for [_ (az/range 1 6)]
        (k/+= count 1)
        (az/break-label outer)))
    (try (testing/expectEqual 1 count))))

(az/deftest nested-continue-test
  (let [count (k/var 0 :usize)]
    (az/for-loop {:label outer} [_ (az/range 1 9)]
      (k/for [_ (az/range 1 6)]
        (k/+= count 1)
        (k/continue outer)))
    (try (testing/expectEqual 8 count))))

(comment
  (nested-break-test)
  (nested-continue-test))
