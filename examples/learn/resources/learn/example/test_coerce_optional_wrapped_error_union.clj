(ns learn.example.test-coerce-optional-wrapped-error-union
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest coerce-to-optionals-wrapped-in-error-union
  (let [x (as 1234 [:error-union :anyerror [:optional :i32]])
        y (as nil [:error-union :anyerror [:optional :i32]])]
    (k/try (testing/expectEqual 1234 (a/unwrap (k/try x))))
    (k/try (testing/expectEqual nil (k/try y)))))

(comment
  (coerce-to-optionals-wrapped-in-error-union))
