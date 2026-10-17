(ns learn.example.test-call-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- add :i32
  [[a :i32] [b :i32]]
  (+ a b))

(a/deftest noinline-function-call
  (k/try (testing/expectEqual 12 (call :auto add [3 9]))))

(comment
  (noinline-function-call))
