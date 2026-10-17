(ns aguafria.zig.dispatch-error-prepared-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defstruct FailureBox [[:result [:error-union :anyerror :i32]]])

(a/defn native-failure :!i32 [[fail? :bool]]
  (if fail? (a/error-value :PreparedFailure) (as 0 :i32)))

(a/defn make-box FailureBox [[fail? :bool]]
  (FailureBox {:result (if fail? (a/error-value :PreparedBoxFailure) (as 0 :i32))}))

(a/deftest named-errors
  (k/try (testing/expectError (a/error-value :PreparedFailure) (native-failure true)))
  (let [box (make-box true)]
    (k/try (testing/expectError (a/error-value :PreparedBoxFailure) (:result box)))))
