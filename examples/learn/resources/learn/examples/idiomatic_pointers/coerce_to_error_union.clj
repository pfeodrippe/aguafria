(ns learn.examples.idiomatic-pointers.coerce-to-error-union
  (:require aguafria.std
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest error-union-coercion-test
  (let [^{:zig/type [:error-union :anyerror :i32]} success 1234
        ^{:zig/type [:error-union :anyerror :i32]} failure (az/error-value :Failure)]
    (try (testing/expectEqual 1234 (try success)))
    (try (testing/expectError (az/error-value :Failure) failure))))
