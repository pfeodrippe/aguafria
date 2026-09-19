(ns learn.examples.idiomatic-state.container-level-variables
  (:require aguafria.std
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defvar y :i32 (add 10 x))
(az/defconst x :i32 (add 12 34))

(az/deftest container-level-variables-test
  (try (testing/expectEqual 46 x))
  (try (testing/expectEqual 56 y)))

(az/defn- add :i32
  [[a :i32] [b :i32]]
  (+ a b))
