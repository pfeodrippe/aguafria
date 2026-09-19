(ns learn.example.test-coerce-optionals
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest optional-coercion-test
  (let [^{:zig/type [:optional :i32]} present 1234
        ^{:zig/type [:optional :i32]} absent nil]
    (try (testing/expectEqual 1234 (az/unwrap present)))
    (try (testing/expectEqual nil absent))))
