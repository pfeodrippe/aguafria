(ns learn.example.test-container-level-variables
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- add :i32
  [[a :i32] [b :i32]]
  (+ a b))

(az/defconst x :i32 (add 12 34))
(az/defvar y :i32 (add 10 x))

(az/deftest container-level-variables-test
  (try (testing/expectEqual 46 x))
  (try (testing/expectEqual 56 y)))

(comment
  (container-level-variables-test))
