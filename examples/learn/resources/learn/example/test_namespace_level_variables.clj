(ns learn.example.test-namespace-level-variables
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- add :i32
  [[a :i32] [b :i32]]
  (k/+ a b))

(a/defconst x :i32 (add 12 34))
(a/defvar y :i32 (add 10 x))

(a/deftest container-level-variables
  (try (testing/expectEqual 46 x))
  (try (testing/expectEqual 56 y)))

(comment
  (container-level-variables))
