(ns aguafria.zig.callable-deftest-test
  (:require [aguafria.zig :as az]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io StringWriter]))

(defn- scratch-namespace []
  (let [namespace (create-ns (gensym "aguafria.callable-test-fixture-"))]
    (binding [*ns* namespace]
      (refer 'clojure.core)
      (require '[aguafria.zig :as az])
      (require '[aguafria.keyword :as ak]))
    namespace))

(defn- capture-execution [invoke]
  (let [out (StringWriter.)
        err (StringWriter.)
        outcome (binding [*out* out *err* err]
                  (try {:result (invoke)}
                       (catch clojure.lang.ExceptionInfo failure
                         {:failure failure})))]
    (assoc outcome :printed-out (str out) :printed-err (str err))))

(deftest deftest-var-is-a-zero-argument-native-callable
  (let [namespace (scratch-namespace)
        registered (atom [])
        invocations (atom [])]
    (try
      (with-redefs [runtime/register-declaration! #(swap! registered conj %)
                    runtime/run-test! (fn [module test-name]
                                        (swap! invocations conj [module test-name])
                                        {:status :succeeded :test test-name})]
        (let [test-var (binding [*ns* namespace]
                         (eval '(az/deftest body-is-native
                                  "A native-only test body."
                                  (try (only-native-values (az/type [:slice-const :u8]))))))
              metadata (meta test-var)]
          (is (var? test-var))
          (is (fn? (var-get test-var)))
          (is (= '([]) (:arglists metadata)))
          (is (= "A native-only test body." (:doc metadata)))
          (is (true? (:aguafria/test metadata)))
          (is (= :test (get-in metadata [:aguafria/declaration :kind])))
          (is (= 'body-is-native (get-in metadata [:aguafria/declaration :name])))
          (is (= "body-is-native" (get-in metadata [:aguafria/declaration :test-name])))
          (is (= (:aguafria/declaration metadata) (first @registered)))
          (is (= {:status :succeeded :test 'body-is-native} (test-var)))
          (is (= [[(str (ns-name namespace)) 'body-is-native]] @invocations))
          (is (thrown? clojure.lang.ArityException (test-var :extra-argument)))))
      (finally
        (remove-ns (ns-name namespace))))))

(deftest deftest-redefinition-retains-the-var-and-updates-the-descriptor
  (let [namespace (scratch-namespace)]
    (try
      (with-redefs [runtime/register-declaration! identity]
        (binding [*ns* namespace]
          (let [first-var (eval '(az/deftest same-test (native-first)))
                first-body (get-in (meta first-var) [:aguafria/declaration :body])
                second-var (eval '(az/deftest same-test (native-second)))]
            (is (identical? first-var second-var))
            (is (not= first-body (get-in (meta second-var) [:aguafria/declaration :body])))
            (is (= '([]) (:arglists (meta second-var)))))))
      (finally
        (remove-ns (ns-name namespace))))))

(deftest unknown-native-test-is-rejected-before-starting-zig
  (let [failure (try
                  (runtime/run-test! "aguafria.test.unknown-module" 'missing-test)
                  nil
                  (catch clojure.lang.ExceptionInfo error error))]
    (is (some? failure))
    (is (= :zig-test-selection (:aguafria/phase (ex-data failure))))
    (is (= 'missing-test (:test (ex-data failure))))))

(deftest ^:integration native-callable-selects-root-test-and-preserves-native-types
  (let [dependency (scratch-namespace)
        root (scratch-namespace)]
    (try
      (binding [*ns* dependency runtime/*source-only-registration?* true]
        (eval '(az/defn checked-length [:error-union :anyerror :usize]
                 [[values [:slice-const :i32]]]
                 (az/field values :len)))
        (eval '(az/deftest foo
                 (ak/compileError "Imported foo must not be compiled or run"))))
      (binding [*ns* root runtime/*source-only-registration?* true]
        (require 'aguafria.std)
        (require '[aguafria.std.testing :as testing])
        (alias 'dependency (ns-name dependency))
        (eval '(az/defn pair-type :type [[T {:zig/prefix "comptime"} :type]]
                 (az/type [:array 2 T])))
        (eval '(az/deftest foo-extra
                 (ak/compileError "Sibling foo-extra must not be compiled or run")))
        (eval '(az/deftest foo
                 (let [values (az/array-init (pair-type (az/type :i32)) [20 22])]
                   (try (testing/expectEqual 2 (try (dependency/checked-length (& values)))))
                   (try (testing/expectEqual 42 (+ (az/index values 0) (az/index values 1))))))))
      (let [test-var (ns-resolve root 'foo)
            before (:aguafria/declaration (meta test-var))
            {:keys [result failure printed-out printed-err]} (capture-execution test-var)]
        (is (nil? failure) (some-> failure ex-message))
        (when result
          (is (= :passed (:status result)))
          (is (zero? (:exit result)))
          (is (= (symbol (str (ns-name root)) "foo") (:test result)))
          (is (= #{:test :status :exit :duration-ms} (set (keys result))))
          (let [details (:aguafria/test-result (meta result))]
            (is (= "foo" (:test-name details)))
            (is (= (runtime/zig-executable) (first (:command details))))
            (is (= ["test" "--test-filter" "native_test.test.foo"]
                   (subvec (:command details) 1 4)))
            (is (= printed-out (:stdout details)))
            (is (= printed-err (:stderr details)))
            (is (str/includes? (str printed-out printed-err) "native_test.test.foo"))
            (is (not (str/includes? (str printed-out printed-err) "foo-extra")))
            (is (some #{(str (ns-name dependency))} (:dependencies details)))
            (let [source (slurp (:source-path details))]
              (is (str/includes? source "test \"foo\""))
              (is (not (str/includes? source "test \"foo-extra\"")))))
          (is (= before (:aguafria/declaration (meta test-var))))))
      (finally
        (remove-ns (ns-name root))
        (remove-ns (ns-name dependency))))))

(deftest ^:integration callable-test-reports-runtime-and-compile-failures
  (let [namespace (scratch-namespace)]
    (try
      (binding [*ns* namespace runtime/*source-only-registration?* true]
        (require 'aguafria.std)
        (require '[aguafria.std.testing :as testing])
        (eval '(az/deftest failure-test (try (testing/expect true)))))
      (let [test-var (ns-resolve namespace 'failure-test)
            retained-callable (var-get test-var)]
        ;; Even a retained function value resolves the currently registered test.
        (binding [*ns* namespace runtime/*source-only-registration?* true]
          (eval '(az/deftest failure-test (try (testing/expect false)))))
        (let [{:keys [failure printed-out printed-err]} (capture-execution retained-callable)
              details (ex-data failure)]
          (is (some? failure))
          (is (= :zig-test (:aguafria/phase details)))
          (is (= :failed (:status details)))
          (is (not (zero? (:exit details))))
          (is (= printed-out (:stdout details)))
          (is (= printed-err (:stderr details)))
          (is (str/includes? (str printed-out printed-err) "FAIL")))
        (binding [*ns* namespace runtime/*source-only-registration?* true]
          (eval '(az/deftest failure-test
                   (ak/compileError "callable-test-compile-diagnostic"))))
        (let [{:keys [failure]} (capture-execution test-var)
              details (ex-data failure)]
          (is (some? failure))
          (is (= :zig-test (:aguafria/phase details)))
          (is (seq (:diagnostics details)))
          (is (str/includes? (:stderr details) "callable-test-compile-diagnostic"))
          (is (.isFile (io/file (:source-path details))))))
      (finally
        (remove-ns (ns-name namespace))))))
