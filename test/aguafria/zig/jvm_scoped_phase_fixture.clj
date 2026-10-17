(ns aguafria.zig.jvm-scoped-phase-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst LeftError (a/type [:error-set [:Left]]))
(a/defconst RightError (a/type [:error-set [:Right]]))
(a/defn left-error LeftError [] (a/error-value :Left))
(a/defn right-error RightError [] (a/error-value :Right))

(a/defn phase-error
  (switch kind 0 LeftError 1 RightError)
  [[kind {:attrs #{comptime}} :u1]]
  (switch kind 0 (left-error) 1 (right-error)))

(a/defn phase-width :usize [[kind {:attrs #{comptime}} :u1]]
  (switch kind
    0 (bitSizeOf (TypeOf kind))
    1 (bitSizeOf (TypeOf kind))))

(a/deftest phase-results
  (try (testing/expectEqual (a/error-value :Left) (phase-error 0)))
  (try (testing/expectEqual (a/error-value :Right) (phase-error 1)))
  (try (testing/expectEqual 1 (phase-width 0)))
  (try (testing/expectEqual 1 (phase-width 1)))
  (let [typed (as 1 :u1)]
    (= :_ typed)))
