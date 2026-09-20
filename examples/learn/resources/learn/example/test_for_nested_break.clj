(ns learn.example.test-for-nested-break
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest nested-break-test
  (let [^{:var :usize} count 0]
    (az/for-loop {:label outer} [_ (az/op ".." 1 6)]
      (for [_ (az/op ".." 1 6)]
        (ak/+= count 1)
        (az/break-label outer)))
    (try (testing/expectEqual 1 count))))

(az/deftest nested-continue-test
  (let [^{:var :usize} count 0]
    (az/for-loop {:label outer} [_ (az/op ".." 1 9)]
      (for [_ (az/op ".." 1 6)]
        (ak/+= count 1)
        (ak/continue outer)))
    (try (testing/expectEqual 8 count))))

(comment
  (nested-break-test)
  (nested-continue-test))
