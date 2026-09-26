(ns learn.example.test-container-level-variables
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- add :i32
  [[a :i32] [b :i32]]
  (k/+ a b))

(az/defconst x :i32 (add 12 34))
(az/defvar y :i32 (add 10 x))

(az/deftest container-level-variables
  (try (testing/expectEqual 46 x))
  (try (testing/expectEqual 56 y)))

(comment
  (container-level-variables))
