(ns learn.example.testing-namespace
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest expectEqual-demo
  (let [expected (k/i32 42)
        actual 42]
    ;; The first argument to `expectEqual` is the known, expected, result.
    ;; The second argument is the result of some expression.
    ;; The actual's type is casted to the type of expected.
    (try (testing/expectEqual expected actual))))

(az/deftest expectError-demo
  (let [expected-error (az/error-value :DemoError)
        actual-error-union (k/as (az/error-value :DemoError) [:error-union :anyerror :void])]
    ;; `expectError` will fail when the actual error is different than
    ;; the expected error.
    (try (testing/expectError expected-error actual-error-union))))

(comment
  (expectEqual-demo)
  (expectError-demo))
