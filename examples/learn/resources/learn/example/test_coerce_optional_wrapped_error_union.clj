(ns learn.example.test-coerce-optional-wrapped-error-union
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest coerce-to-optionals-wrapped-in-error-union
  (let [x (k/as 1234 [:error-union :anyerror [:optional :i32]])
        y (k/as nil [:error-union :anyerror [:optional :i32]])]
    (try (testing/expectEqual 1234 (az/unwrap (try x))))
    (try (testing/expectEqual nil (try y)))))

(comment
  (coerce-to-optionals-wrapped-in-error-union))
