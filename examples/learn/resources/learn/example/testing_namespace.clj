(ns learn.example.testing-namespace
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest expect-equal-test
  (let [expected (k/i32 42)
        actual 42]
    ;; The first argument is the known, expected result.
    ;; The second is the result of an expression.
    ;; The actual value is cast to the expected value's type.
    (try (testing/expectEqual expected actual))))

(az/deftest expect-error-test
  (let [expected-error (az/error-value :DemoError)
        actual-error-union (k/as (az/error-value :DemoError) [:error-union :anyerror :void])]
    ;; `expectError` fails when the actual error differs from the expected error.
    (try (testing/expectError expected-error actual-error-union))))

(comment
  (expect-equal-test)
  (expect-error-test))
