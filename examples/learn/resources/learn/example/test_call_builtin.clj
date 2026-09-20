(ns learn.example.test-call-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest call-builtin-test
  (try (testing/expectEqual 12 (ak/call :.auto add [3 9]))))

(az/defn- add :i32
  [[a :i32] [b :i32]]
  (+ a b))

(comment
  (call-builtin-test))
