(ns learn.example.test-call-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- add :i32
  [[a :i32] [b :i32]]
  (k/+ a b))

(az/deftest noinline-function-call
  (try (testing/expectEqual 12 (k/call :.auto add [3 9]))))

(comment
  (noinline-function-call))
