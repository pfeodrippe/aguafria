(ns learn.example.test-coerce-optional-wrapped-error-union
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-error-union-coercion-test
  (let [^{:zig/type [:error-union :anyerror [:optional :i32]]} present 1234
        ^{:zig/type [:error-union :anyerror [:optional :i32]]} absent nil]
    ;; Unwrap the successful error union before inspecting its optional payload.
    (try (testing/expectEqual 1234 (az/unwrap (try present))))
    (try (testing/expectEqual nil (try absent)))))
