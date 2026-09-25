(ns learn.example.test-coerce-optional-wrapped-error-union
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-error-union-coercion-test
  (let [present (k/as 1234 [:error-union :anyerror [:optional :i32]])
        absent (k/as nil [:error-union :anyerror [:optional :i32]])]
    ;; Unwrap the successful error union before inspecting its optional payload.
    (try (testing/expectEqual 1234 (az/unwrap (try present))))
    (try (testing/expectEqual nil (try absent)))))

(comment
  (optional-error-union-coercion-test))
