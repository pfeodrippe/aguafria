(ns aguafria.zig.discovery-try-operand-fixture
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn checked [:error-union :i32] [[input :i32]]
  input)

(a/deftest checked-result
  (let [result (try (checked 1234))
        alias result]
    (try (testing/expectEqual 1234 alias))))

(a/deftest shadowed-result
  (let [result (try (checked 4321))]
    (try (testing/expectEqual 4321 result)))
  (let [result 9876]
    (try (testing/expectEqual 9876 result))))
