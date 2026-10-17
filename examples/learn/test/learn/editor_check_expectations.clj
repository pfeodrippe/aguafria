(ns learn.editor-check-expectations
  "Reviewed intentional negatives, not automatic acceptance of arbitrary errors.
  Each contract names the actual editor stage, exception class and diagnostic.
  A blocked compiler command or fallback still fails the public strict audit.")

(def exceptions
  {'learn.example.constant-identifier-cannot-change
   {:stage :load :type 'clojure.lang.ExceptionInfo
    :message-pattern "error\\[aguafria::zig\\]: cannot assign to constant"
    :data {:aguafria/phase :zig-compile :aguafria/prepared-rejection? true :exit 1
           :module "learn.example.constant-identifier-cannot-change"
           :clojure.error/phase :compile-syntax-check
           :clojure.error/symbol 'learn.example.constant-identifier-cannot-change/foo
           :clojure.error/line 13 :clojure.error/column 5}}

   'learn.example.var-must-be-initialized
   {:stage :load :type 'clojure.lang.ExceptionInfo
    :message-pattern "^let expects an even Clojure binding vector; every local needs an initial value$"
    :data {:clojure.error/phase :compile-syntax-check}}

   ;; Observed through the exact strict editor workload, not inferred from a
   ;; blocked compiler command or a substituted native test runner.
   'learn.example.test-basic-slices
   {:stage :evaluate :type 'clojure.lang.ExceptionInfo
    :message-pattern "^Native Zig panic: index out of bounds: index 10, len 4(?:\\n|$)"
    :data {:aguafria/phase :native-panic
           :panic-message "index out of bounds: index 10, len 4"}}

   'learn.example.test-sentinel-mismatch
   {:stage :evaluate :type 'clojure.lang.ExceptionInfo
    :message-pattern "^Native Zig panic: sentinel mismatch: expected 0, found 1(?:\\n|$)"
    :data {:aguafria/phase :native-panic
           :panic-message "sentinel mismatch: expected 0, found 1"}}

   'learn.example.bad-default-value
   {:stage :evaluate :type 'clojure.lang.ExceptionInfo
    :message-pattern "(?s)^Native Zig panic: reached unreachable code.*bad_default_value\\.clj:17:15"
    :data {:aguafria/phase :native-panic :panic-message "reached unreachable code"
           :clojure.error/phase :execution :clojure.error/line 17 :clojure.error/column 15}}

   'learn.example.test-wrong-union-access
   {:stage :evaluate :type 'clojure.lang.ExceptionInfo
    :message-pattern "^Native Zig panic: access of union field 'float' while field 'int' is active(?:\\n|$)"
    :data {:aguafria/phase :native-panic
           :panic-message "access of union field 'float' while field 'int' is active"}}

   'learn.example.test-blocks
   {:stage :load :type 'clojure.lang.ExceptionInfo
    :message-pattern "^Unresolved Zig reference `x`\\. Define it before use\\.$"
    :data {:clojure.error/phase :compile-syntax-check
           :form 'x :symbol 'x :context-ns 'learn.example.test-blocks}}

   'learn.example.testing-failure
   {:stage :evaluate :type 'clojure.lang.ExceptionInfo
    :message-pattern "^Native Zig error: TestUnexpectedResult$"
    :data {:aguafria/phase :native-error :error-name :TestUnexpectedResult}}

   'learn.example.test-incorrect-pointer-alignment
   {:stage :evaluate :type 'clojure.lang.ExceptionInfo
    :message-pattern "^Native Zig panic: incorrect alignment(?:\\n|$)"
    :data {:panic-message "incorrect alignment"}}})

(defn expectation [namespace]
  (if-let [exception (get exceptions namespace)]
    {:exception exception}
    {:status :completed}))
