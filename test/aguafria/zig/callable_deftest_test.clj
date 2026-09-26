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
                       (catch Exception failure
                         {:failure failure})))]
    (assoc outcome :printed-out (str out) :printed-err (str err))))

(deftest inferred-error-keywords-call-and-hot-reload-natively
  (let [namespace (scratch-namespace)
        returned (atom [])
        call-value (fn [f argument]
                     (let [result (f argument)]
                       (swap! returned conj result)
                       (az/value result)))]
    (try
      (binding [*ns* namespace]
        (eval '(az/defn checked :!u32 [[fail? :bool]]
                 (when fail? (ak/return (az/error-value :NoValue)))
                 42))
        (eval '(az/defn checked-void :!void [[fail? :bool]]
                 (when fail? (ak/return (az/error-value :NoValue)))))
        (eval '(az/defn recursive :!u32 [[remaining :u32]]
                 (if (ak/== remaining 0)
                   7
                   (try (recursive (- remaining 1)))))))
      (let [checked (ns-resolve namespace 'checked)
            checked-void (ns-resolve namespace 'checked-void)
            recursive (ns-resolve namespace 'recursive)]
        (is (= {:ok 42} (call-value checked false)))
        (is (= :NoValue (get-in (call-value checked true) [:error :name])))
        (is (= {:ok nil} (call-value checked-void false)))
        (is (= :NoValue (get-in (call-value checked-void true) [:error :name])))
        (is (= {:ok 7} (call-value recursive 3)))
        (binding [*ns* namespace]
          (eval '(az/defn checked :!u32 [[fail? :bool]]
                   (when fail? (ak/return (az/error-value :NoValue)))
                   99)))
        (is (= {:ok 99} (call-value checked false)))
        (is (= :NoValue (get-in (call-value checked true) [:error :name]))))
      (finally
        (doseq [result @returned]
          (az/close! result))
        (remove-ns (ns-name namespace))))))

(deftest inferred-error-payloads-resolve-local-and-imported-type-vars
  (let [provider (scratch-namespace)
        consumer (scratch-namespace)
        returned (atom [])]
    (try
      (binding [*ns* provider]
        (eval '(az/defstruct Packet [[:code :u32]]))
        (eval '(az/defn packet :!Packet [] (Packet {:code 11})))
        (eval '(az/defconst Bytes (az/type [:slice-const :u8])))
        (eval '(az/defn payload-bytes :!Bytes [] "payload"))
        (eval '(az/defn composite-result [:! [:slice-const :u8]] [] "composite")))
      (binding [*ns* consumer]
        (alias 'provider (ns-name provider))
        (eval '(az/defn imported-packet :!provider/Packet []
                 (try (provider/packet))))
        (eval '(az/defn optional-result [:! [:optional :u32]]
                 [[present? :bool]]
                 (if present? (ak/as 42 :u32) nil))))
      (doseq [[namespace function arguments expected]
              [[provider 'packet [] {:ok {:code 11}}]
               [provider 'payload-bytes [] {:ok (mapv int "payload")}]
               [provider 'composite-result [] {:ok (mapv int "composite")}]
               [consumer 'imported-packet [] {:ok {:code 11}}]
               [consumer 'optional-result [true] {:ok 42}]
               [consumer 'optional-result [false] {:ok nil}]]]
        (let [result (apply (ns-resolve namespace function) arguments)]
          (swap! returned conj result)
          (is (= expected (az/value result)) (str function))))
      (finally
        (doseq [result @returned] (az/close! result))
        (remove-ns (ns-name consumer))
        (remove-ns (ns-name provider))))))

(deftest deftest-var-is-a-zero-argument-native-callable
  (let [namespace (scratch-namespace)
        registered (atom [])
        invocations (atom [])]
    (try
      (with-redefs [runtime/check-test-definition! identity
                    runtime/register-declaration! #(swap! registered conj %)
                    runtime/run-test! (fn [module test-name]
                                        (swap! invocations conj [module test-name])
                                        {:status :succeeded :test test-name})]
        (let [test-var (binding [*ns* namespace]
                         (eval '(declare only-native-values))
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
      (with-redefs [runtime/check-test-definition! identity
                    runtime/register-declaration! identity]
        (binding [*ns* namespace]
          (eval '(declare native-first native-second))
          (let [first-var (eval '(az/deftest same-test (native-first)))
                first-body (get-in (meta first-var) [:aguafria/declaration :body])
                second-var (eval '(az/deftest same-test (native-second)))]
            (is (identical? first-var second-var))
            (is (not= first-body (get-in (meta second-var) [:aguafria/declaration :body])))
            (is (= '([]) (:arglists (meta second-var)))))))
      (finally
        (remove-ns (ns-name namespace))))))

(deftest test-definitions-check-current-declarations-without-running
  (let [namespace (scratch-namespace)
        module (str (ns-name namespace))
        define (fn [form]
                 (capture-execution #(binding [*ns* namespace] (eval form))))]
    (try
      (doseq [form ['(az/deftest missing-function (later-helper))
                    '(az/deftest missing-value (ak/= :_ later-value))]]
        (let [{:keys [failure]} (define form)
              test-name (second form)
              test-var (ns-resolve namespace test-name)]
          (is (some? failure))
          (is (str/includes? (ex-message (last (take-while some? (iterate ex-cause failure))))
                             "Unresolved Zig reference"))
          ;; Like Clojure def, compilation may intern an unbound Var. It must
          ;; never publish a callable or register the rejected declaration.
          (is (or (nil? test-var) (not (bound? test-var))))
          (is (nil? (get-in @@#'runtime/registry
                           [module :definitions [:test test-name]])))))
      (is (nil? (:failure (define '(az/defn- later-helper :void [])))))
      (let [{:keys [failure result printed-out printed-err]}
            (define '(az/deftest checked-test
                       (later-helper)
                       (ak/panic "Only fail when the test is called")))]
        (is (nil? failure) (some-> failure str))
        (is (var? result))
        (is (= "" (str printed-out printed-err)))
        (when (var? result)
          (let [previous @result
                descriptor (:aguafria/declaration (meta result))
                rejected (define '(az/deftest checked-test (still-missing)))]
            (is (some? (:failure rejected)))
            (is (identical? previous @result))
            (is (= descriptor (:aguafria/declaration (meta result))))
            (is (= (:body descriptor)
                   (get-in @@#'runtime/registry
                           [module :definitions [:test 'checked-test] :body]))))
          (is (str/includes? (str (:failure (capture-execution result)))
                             "Only fail when the test is called"))))
      (finally
        (remove-ns (ns-name namespace))))))

(deftest unknown-references-fail-before-any-registration-mode
  (doseq [mode [:ordinary :source-only :batch]
          form ['(az/defn- caller :void [] (unknown-function))
                '(az/defn- generic-caller :void [[T {:zig/prefix "comptime"} :type]]
                   (unknown-function T))
                '(az/defconst missing-constant :i32 later-value)
                '(az/defvar missing-state :i32 (later-helper 1))
                '(az/defstruct Missing [[:field UnknownType]])
                '(az/deftest missing-test (unknown-function))
                '(az/defn- bad-local :i32 [] (let [x y y 1] x))]]
    (let [namespace (scratch-namespace)]
      (try
        (let [failure (binding [*ns* namespace
                                runtime/*source-only-registration?* (= mode :source-only)
                                runtime/*registration-batch* (when (= mode :batch) (atom []))]
                        (try (eval form) nil (catch Exception error error)))]
          (is (some? failure) (str mode " " form))
          (is (str/includes? (ex-message (last (take-while some? (iterate ex-cause failure))))
                             "Unresolved Zig reference")
              (str mode " " form)))
        (finally (remove-ns (ns-name namespace)))))))

(deftest programmatic-batches-also-require-declaration-order
  (let [namespace (scratch-namespace)
        module (str (ns-name namespace))
        helper {:kind :fn :name 'helper :declaration-key [:fn 'helper]
                :module module :return :i32 :args []
                :body [7] :implicit-return? true}
        caller {:kind :fn :name 'caller :declaration-key [:fn 'caller]
                :module module :return :i32 :args []
                :body ['(helper)] :implicit-return? true}]
    (try
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved Zig reference"
                            (runtime/register-batch! [caller helper] {:compile? false})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unresolved Zig reference"
                            (binding [runtime/*source-only-registration?* true]
                              (runtime/register-declaration! caller))))
      (is (= 2 (:declaration-count
                (runtime/register-batch! [helper caller] {:compile? false}))))
      (finally
        (remove-ns (ns-name namespace))
        (swap! @#'runtime/registry dissoc module)))))

(deftest loading-a-file-rejects-a-test-before-its-helper
  (let [namespace 'fixture.test-before-helper]
    (try
      (let [{:keys [failure]}
            (capture-execution #(load-file "test/fixtures/test_before_helper.clj"))]
        (is (some? failure))
        (is (str/includes? (str failure) "Unresolved Zig reference"))
        (is (str/includes? (str failure) "test_before_helper.clj"))
        (is (nil? (ns-resolve namespace 'later-helper))))
      (finally
        (remove-ns namespace)
        (swap! @#'runtime/registry dissoc (str namespace))))))

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
                 (let [values (az/init [20 22] (pair-type (az/type :i32)))]
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
            (is (= ["test" "--test-filter"] (subvec (:command details) 1 3)))
            (is (some #{"--test-no-exec"} (:command details)))
            (is (some #{"-fno-emit-bin"} (:command details)))
            (is (= :in-process (:execution details)))
            (is (= printed-out (:stdout details)))
            (is (= printed-err (:stderr details)))
            (is (str/includes? (str printed-out printed-err) ".test.foo"))
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
              details (runtime/error-data failure)]
          (is (some? failure))
          (is (= :zig-test (:aguafria/phase details)))
          (is (= :failed (:status details)))
          (is (not (zero? (:exit details))))
          (is (= printed-out (:stdout details)))
          (is (= printed-err (:stderr details)))
          (is (str/includes? (str printed-out printed-err) "FAIL"))
          (is (not (str/includes? (ex-message failure) printed-err)))
          (is (= 1 (count (re-seq #"FAIL \(TestUnexpectedResult\)"
                                  (str printed-out printed-err (ex-message failure)))))))
        (binding [*ns* namespace runtime/*source-only-registration?* true]
          (eval '(az/deftest failure-test
                   (ak/compileError "callable-test-compile-diagnostic"))))
        (let [{:keys [failure]} (capture-execution test-var)
              details (runtime/error-data failure)]
          (is (some? failure))
          (is (instance? clojure.lang.Compiler$CompilerException failure))
          (is (= :zig-test (:aguafria/phase details)))
          (is (seq (:diagnostics details)))
          (is (str/includes? (:stderr details) "callable-test-compile-diagnostic"))
          (is (.isFile (io/file (:source-path details))))))
      (finally
        (remove-ns (ns-name namespace))))))

(deftest ^:integration native-test-runs-in-the-jvm-process
  (let [namespace (scratch-namespace)
        pid (.pid (java.lang.ProcessHandle/current))]
    (try
      (binding [*ns* namespace runtime/*source-only-registration?* true]
        (require '[aguafria.std.testing :as testing])
        (eval '(az/defextern getpid :c_int {:zig/prefix "extern"}  []))
        (eval (list 'az/deftest 'same-process-test
                    (list 'try (list 'testing/expectEqual
                                     (list 'ak/as pid :c_int) '(getpid))))))
      (let [{:keys [result failure]} (capture-execution (ns-resolve namespace 'same-process-test))]
        (is (nil? failure) (some-> failure ex-message))
        (is (= :passed (:status result)))
        (is (= :in-process (get-in (meta result) [:aguafria/test-result :execution]))))
      (finally (remove-ns (ns-name namespace))))))

(deftest ^:integration native-tests-report-skips-and-allocator-leaks
  (let [namespace (scratch-namespace)]
    (try
      (binding [*ns* namespace runtime/*source-only-registration?* true]
        (require '[aguafria.std.testing :as testing])
        (eval '(az/deftest skipped-test (ak/return (az/error-value :SkipZigTest))))
        (eval '(az/deftest leak-test
                 (ak/= :_ (try ((az/field testing/allocator :alloc) :u8 10))))))
      (is (= :skipped (:status (:result (capture-execution (ns-resolve namespace 'skipped-test))))))
      (let [{:keys [failure printed-err]} (capture-execution (ns-resolve namespace 'leak-test))]
        (is (= :failed (:status (ex-data failure))))
        (is (str/includes? printed-err "leaked memory"))
        (is (= printed-err (:stderr (ex-data failure))))
        (is (= 1 (count (re-seq #"1 test leaked memory"
                                (str printed-err (ex-message failure)))))))
      (finally (remove-ns (ns-name namespace))))))

(deftest array-list-leak-is-a-test-scope-failure-not-a-call-failure
  (let [namespace (scratch-namespace)]
    (try
      (binding [*ns* namespace runtime/*source-only-registration?* true
                *file* (.getCanonicalPath
                        (io/file (io/resource "aguafria/zig/callable_deftest_test.clj")))]
        (require '[aguafria.std :as std] '[aguafria.std.testing :as testing])
        (eval '(az/deftest detect-leak-test
                 (let [allocator testing/allocator
                       list (ak/var :.empty (std/ArrayList :u21))]
                   (try ((az/field list :append) allocator \☔))
                   (try (testing/expectEqual 1 (az/field (az/field list :items) :len))))))
        (eval '(az/deftest cleaned-up-test
                 (let [allocator testing/allocator
                       list (ak/var :.empty (std/ArrayList :u21))]
                   (ak/defer ((az/field list :deinit) allocator))
                   (try ((az/field list :append) allocator \☔))
                   (try (testing/expectEqual 1 (az/field (az/field list :items) :len)))))))
      (let [{:keys [failure printed-err]}
            (capture-execution (ns-resolve namespace 'detect-leak-test))]
        (is (instance? clojure.lang.ExceptionInfo failure))
        (is (= :failed (:status (ex-data failure))))
        (is (str/includes? printed-err "1 test leaked memory"))
        (is (not (str/includes? (str failure printed-err) "count not supported")))
        (is (str/includes? printed-err "Aguafria source locations:"))
        (is (str/includes? printed-err "(try ((az/field list :append) allocator \\☔))"))
        (is (str/ends-with? (:clojure.error/source (ex-data failure))
                           "aguafria/zig/callable_deftest_test.clj"))
        (is (= :execution (:clojure.error/phase (ex-data failure))))
        (is (= printed-err (:stderr (ex-data failure)))))
      (let [{:keys [result failure]}
            (capture-execution (ns-resolve namespace 'cleaned-up-test))]
        (is (nil? failure))
        (is (= :passed (:status result))))
      (finally (remove-ns (ns-name namespace))))))
