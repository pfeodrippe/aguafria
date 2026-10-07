(ns aguafria.zig.jvm-scoped-phase-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst LeftError (a/type [:error-set [:Left]]))
(a/defconst RightError (a/type [:error-set [:Right]]))
(a/defn left-error LeftError [] (a/error-value :Left))
(a/defn right-error RightError [] (a/error-value :Right))

(a/defn phase-error
  (switch kind (case [0] LeftError) (case [1] RightError))
  [[kind {:attrs #{k/comptime}} :u1]]
  (switch kind (case [0] (left-error)) (case [1] (right-error))))

(a/defn phase-width :usize [[kind {:attrs #{k/comptime}} :u1]]
  (switch kind
    (case [0] (k/bitSizeOf (k/TypeOf kind)))
    (case [1] (k/bitSizeOf (k/TypeOf kind)))))

(a/deftest phase-results
  (try (testing/expectEqual (a/error-value :Left) (phase-error 0)))
  (try (testing/expectEqual (a/error-value :Right) (phase-error 1)))
  (try (testing/expectEqual 1 (phase-width 0)))
  (try (testing/expectEqual 1 (phase-width 1)))
  (let [typed (k/as 1 :u1)]
    (k/= :_ typed)))
