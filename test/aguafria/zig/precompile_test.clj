(ns aguafria.zig.precompile-test
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.testing :as zig-testing]
            [aguafria.zig.artifact :as artifact]
            [aguafria.zig.explain :as explain]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.project :as project]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]])
  (:import [java.nio.file Files]
           [java.util.concurrent TimeUnit]))

(deftest eager-import-closure-excludes-unreachable-catalog-modules
  (project/register-catalog!
   {:schema-version 1
    :modules {"aguafria.zig.precompile-eager-test-state-fixture"
              {:source-kind :zig :source-orders {"TestOnly" 0}}
              "aguafria.zig.precompile-eager-this-fixture"
              {:source-kind :zig :source-orders {"Self" 0 "value" 1
                                                 "unused-test-method" 2}}}})
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.precompile-eager-owner-fixture
             'aguafria.zig.precompile-eager-other-dependency-fixture
             'aguafria.zig.precompile-eager-unreachable-fixture :reload))
  (let [owner "aguafria.zig.precompile-eager-owner-fixture"
        constants "aguafria.zig.precompile-eager-constants-fixture"
        dependency "aguafria.zig.precompile-eager-dependency-fixture"
        other-dependency "aguafria.zig.precompile-eager-other-dependency-fixture"
        other-constants "aguafria.zig.precompile-eager-other-constants-fixture"
        test-state "aguafria.zig.precompile-eager-test-state-fixture"
        file-container "aguafria.zig.precompile-eager-this-fixture"
        unreachable "aguafria.zig.precompile-eager-unreachable-fixture"
        declarations (runtime/registered-declarations owner)
        snapshot (assoc (#'runtime/development-dependency-snapshot declarations)
                        unreachable {:module unreachable})
        {:keys [declaration-ids linkage-ids]} (#'runtime/development-capsule-closure
                                               snapshot (#'runtime/development-linkage-logical-ids declarations)
                                               [constants dependency other-dependency file-container] #{})
        names-for (fn [module]
                    (into #{} (comp (filter #(contains? declaration-ids (:logical-id %)))
                                    (map :name))
                          (runtime/registered-declarations module)))]
    (is (= #{'selected 'required-a 'required-b} (names-for constants)))
    (is (= #{'verify-constants} (names-for dependency)))
    (is (= #{'required-c} (names-for other-constants)))
    (is (= #{'verify-other-constants 'inactive-test-state}
           (names-for other-dependency)))
    (is (= #{'TestOnly} (names-for test-state)))
    (is (= #{'Self 'value 'unused-test-method}
           (set (map :name (#'runtime/dependency-live-slice-declarations
                            file-container declaration-ids)))))
    (is (not-any? #(contains? linkage-ids (:logical-id %))
                  (concat (runtime/registered-declarations other-dependency)
                          (runtime/registered-declarations test-state))))
    (is (empty? (names-for unreachable)))))

(defn- eager-import-jvm [cache prepare?]
  (let [code `(do
                (require 'aguafria.zig 'aguafria.zig.runtime 'aguafria.zig.explain
                         'aguafria.zig.project)
                (aguafria.zig/configure! {:cache-dir ~cache})
                ;; Converted namespaces register lazily from their catalog.
                (aguafria.zig.project/register-catalog!
                 {:schema-version 1
                  :modules {"aguafria.zig.precompile-eager-test-state-fixture"
                            {:source-kind :zig :source-orders {"TestOnly" 0}}
                            "aguafria.zig.precompile-eager-this-fixture"
                            {:source-kind :zig :source-orders {"Self" 0 "value" 1
                                                               "unused-test-method" 2}}}})
                (if ~prepare?
                  (with-redefs [aguafria.zig.runtime/invoke!
                                (fn [& _#] (throw (ex-info "Executed native code" {})))
                                aguafria.zig.runtime/invoke-with-result!
                                (fn [& _#] (throw (ex-info "Executed native code" {})))]
                    (let [report# (aguafria.zig/precompile!
                                   {:namespaces ['aguafria.zig.precompile-eager-owner-fixture]
                                    :coercions [:u32]})]
                      (assert (= [:prepared] (mapv :status (:functions report#))))
                      (prn {:prepared true})))
                  (let [events# (atom [])
                        result# (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                                  (require 'aguafria.zig.precompile-eager-owner-fixture)
                                  (aguafria.zig/value
                                   ((resolve 'aguafria.zig.precompile-eager-owner-fixture/selected-value))))]
                    (prn {:result result# :events @events#})))
                (shutdown-agents))
        result (shell/sh (str (System/getProperty "java.home") "/bin/java")
                         "--enable-native-access=ALL-UNNAMED"
                         "-cp" (System/getProperty "java.class.path")
                         "clojure.main" "-e" (pr-str code))]
    (when-not (zero? (:exit result))
      (throw (ex-info "Eager import JVM failed" result)))
    (edn/read-string (:out result))))

(deftest eager-import-prerequisites-reuse-the-bundle-after-restart
  (let [cache (str (Files/createTempDirectory
                    (.toPath (doto (io/file ".aguafria/precompile-tests") .mkdirs))
                    "eager-import-" (make-array java.nio.file.attribute.FileAttribute 0)))
        prepared (eager-import-jvm cache true)
        restarted (eager-import-jvm cache false)
        events (:events restarted)
        library (:path (first (filter #(= :bundle-loaded (:event %)) events)))
        entries (when library
                  (set (keys (:entries
                              (edn/read-string
                               (slurp (io/file (.getParentFile (io/file library))
                                               "manifest.edn")))))))]
    (is (:prepared prepared))
    (is (= 11 (:result restarted)))
    (is (empty? (filter #(#{:compiled :compile-failed} (:event %)) events)) (pr-str events))
    (is (= 1 (count (filter #(= :bundle-loaded (:event %)) events))) (pr-str events))
    (is (seq (filter #(= :bundle-cache-hit (:event %)) events)))
    (is (empty? (filter #(and (= :disk-cache-hit (:event %))
                              (str/starts-with? (or (:module %) "") "aguafria.jvm.")) events))
        (pr-str events))
    (is (every? #(contains? entries
                            (artifact/key-for :bundle-entry [(:module %) (:artifact-key %)]))
                (filter #(= :bundle-cache-hit (:event %)) events)))))

(deftest invalid-options-do-not-start-precompilation
  (doseq [options [nil {} {:namespace ['somewhere]}
                   {:namespaces 'somewhere} {:namespaces ['somewhere/function]}
                   {:warmup ['somewhere/run!]}
                   {:calls [{:function 'unqualified :args []}]}]]
    (is (thrown? clojure.lang.ExceptionInfo (a/precompile! options)))))

(deftest comptime-only-mutation-has-no-runtime-handler
  (let [fail! (fn [& _] (throw (ex-info "Requested native storage for a comptime-only value" {})))]
    (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!
                  runtime/precompile-function! fail!]
      (doseq [target [:comptime_int :comptime_float
                      {:literal 0 :type :comptime_int}
                      {:literal 1.0 :type :comptime_float}]
              operation ["=" "+="]]
        (let [result (jvm/precompile-assignment!
                      {:function 'aguafria.keyword/+= :operation operation
                       :target target :operand :usize})]
          (is (= :unsupported (:status result)))
          (is (= :comptime-only-mutation (:reason result))))))))

(deftest captured-configurations-are-local-to-compile-only-work
  (let [configuration (runtime/configuration)
        captured (assoc configuration :zig-args ["-lc"])]
    (is (thrown? clojure.lang.ExceptionInfo
                 (runtime/call-with-precompile-configuration captured identity)))
    (binding [runtime/*compile-only?* true]
      (is (= captured
             (runtime/call-with-precompile-configuration captured runtime/configuration)))
      (is (= "fast"
             (runtime/call-with-precompile-configuration
              captured #(do (runtime/configure! {:optimize "fast"})
                            (:optimize (runtime/configuration))))))
      (is (= ["-lc"]
             @(future
                (runtime/call-with-precompile-configuration
                 captured #(:zig-args (runtime/configuration)))))))
    (is (= configuration (runtime/configuration)))))

(deftest bundle-failures-preserve-the-completed-preparation-report
  (let [directory (Files/createTempDirectory "aguafria-bundle-failure-report-"
                                             (make-array java.nio.file.attribute.FileAttribute 0))
        file (str (io/file (.toFile directory) "report.edn"))
        failure (with-redefs [jvm/precompile-coercion! #(hash-map :type % :status :prepared)
                              runtime/finish-precompile-bundles!
                              (fn [_] (throw (ex-info "Bundle compile failed"
                                                      {:aguafria/phase :bundle-compile
                                                       :reason :fixture-link-failure})))]
                  (try
                    (precompile/precompile! {:coercions [:i32] :report-file file})
                    nil
                    (catch clojure.lang.ExceptionInfo error (ex-data error))))
        report (edn/read-string (slurp file))]
    (is (= file (:report-file failure)))
    (is (= :fixture-link-failure (:reason failure)))
    (is (= :failed (get-in report [:bundles :status])))
    (is (= :fixture-link-failure (get-in report [:bundles :reason])))
    (is (= :bundle-compile (get-in report [:bundles :aguafria/phase])))
    (is (= [{:type :i32 :status :prepared}] (:coercions report)))
    (is (map? (:coverage report)))))

(deftest namespace-precompilation-does-not-run-bodies
  (let [fail! (fn [& _] (throw (ex-info "Precompilation invoked a native body" {})))
        report (with-redefs [runtime/invoke! fail!
                             runtime/invoke-with-result! fail!]
                 (a/precompile! {:namespaces ['aguafria.zig.precompile-fixture]}))
        statuses (into {} (map (juxt :function :status)) (:functions report))]
    (is (= :prepared (statuses 'aguafria.zig.precompile-fixture/do-not-call)))
    (is (= :prepared (statuses 'aguafria.zig.precompile-fixture/increment)))
    (is (= :prepared (statuses 'aguafria.zig.precompile-fixture/subtract)))
    (is (= :skipped (statuses 'aguafria.zig.precompile-fixture/generic-identity)))
    (is (= :specialization
           (:reason (first (filter #(= 'aguafria.zig.precompile-fixture/generic-identity
                                       (:function %)) (:functions report))))))
    (is (empty? (:calls report)))))

(deftest explicit-signatures-compile-without-invocation
  (let [fail! (fn [& _] (throw (ex-info "Precompilation invoked a native body" {})))
        report (with-redefs [runtime/invoke! fail!
                             runtime/invoke-with-result! fail!]
                 (a/precompile!
                  {:calls '[{:function aguafria.std.debug/assert :args [:bool]}
                            {:function aguafria.std.testing/expectEqual :args [:i32 :i32]}
                            {:function aguafria.zig.precompile-fixture/generic-identity
                             :args [{:comptime :i32} :i32]}]
                   :coercions [:i32]}))
        argument (k/i32 42)
        commands (atom [])
        original shell/sh]
    (is (= 3 (count (:calls report))))
    (with-redefs [shell/sh (fn [& args]
                             (swap! commands conj (vec (take 4 args)))
                             (apply original args))]
      (debug/assert true)
      (zig-testing/expectEqual argument argument)
      (is (= 42 (a/value ((resolve 'aguafria.zig.precompile-fixture/generic-identity)
                          :i32 argument)))))
    (is (empty? @commands) (str @commands))))

(deftest invalid-load-time-test-checks-are-reported-without-running
  (let [fail! (fn [& _] (throw (ex-info "Preparation ran a test" {})))
        images (atom {})
        functions
        (binding [runtime/*compile-only?* true
                  runtime/*source-only-registration?* true
                  runtime/*prepared-namespace-images* images]
          (with-redefs [runtime/run-test! fail!]
            (require 'aguafria.zig.precompile-invalid-test-fixture :reload)
            (runtime/precompile-functions! 'aguafria.zig.precompile-invalid-test-fixture)))
        check (first (get-in @images ["aguafria.zig.precompile-invalid-test-fixture" :test-checks]))]
    (is (= 'aguafria.zig.precompile-invalid-test-fixture/invalid-literal (:test check)))
    (is (= :failed (:status check)))
    (is (str/includes? (:error check) "cannot represent integer value '256'"))
    (is (= :zig-test (get-in check [:details :aguafria/phase])))
    (is (some #(and (= 'aguafria.zig.precompile-invalid-test-fixture/increment (:function %))
                    (= :prepared (:status %))) functions))
    (is (thrown? clojure.lang.Compiler$CompilerException
                 (runtime/check-test-definition!
                  (:aguafria/declaration
                   (meta (resolve 'aguafria.zig.precompile-invalid-test-fixture/invalid-literal))))))))

(deftest host-only-namespaces-have-no-native-preparation-work
  (let [report (a/precompile! {:analyze ['aguafria.zig.precompile-host-fixture]})]
    (is (= [{:namespace 'aguafria.zig.precompile-host-fixture
             :status :skipped :reason :no-native-declarations}]
           (:analysis report)))
    (is (= {:skipped 1} (get-in report [:coverage :namespaces :statuses])))
    (is (zero? (get-in report [:coverage :operations :total])))))

(deftest loaded-callables-still-get-persisted-in-a-new-cache
  (require 'aguafria.zig.precompile-fixture)
  (let [function (resolve 'aguafria.zig.precompile-fixture/generic-identity)
        argument (k/i32 42)
        configuration (runtime/configuration)
        cache (str (Files/createTempDirectory
                    "aguafria-loaded-precompile-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))
        loaded #(into {}
                      (keep (fn [[module state]]
                              (when (seq (:functions state))
                                [module (:functions state)])))
                      @(var-get (ns-resolve 'aguafria.zig.runtime 'registry)))
        events (atom [])]
    (is (= 42 (a/value (function :i32 argument))))
    (let [before (loaded)]
      (is (seq before) "The preservation check must include loaded native bindings")
      (try
        (runtime/configure! {:cache-dir cache})
        (let [report (binding [explain/*reporter* #(swap! events conj %)]
                       (a/precompile!
                        {:calls '[{:function aguafria.zig.precompile-fixture/generic-identity
                                   :args [{:comptime :i32} :i32]}]
                         :bundle? false
                         :report-file (str (io/file cache "report.edn"))}))]
          (is (every? #(= :prepared (:status %)) (:calls report)))
          (is (some #(= :compiled (:event %)) @events))
          (is (every? #(.isFile (io/file (:path %)))
                      (filter #(= :compiled (:event %)) @events)))
          (is (= before (loaded)) "Preparation must not replace loaded native bindings"))
        (finally (runtime/configure! configuration))))))

(deftest already-registered-dependencies-get-startup-images
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.precompile-dependency-fixture))
  (let [configuration (runtime/configuration)
        cache (str (Files/createTempDirectory
                    "aguafria-dependency-precompile-"
                    (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (runtime/configure! {:cache-dir cache})
      (let [report (a/precompile!
                    {:namespaces ['aguafria.zig.precompile-dependent-fixture]
                     :bundle? false
                     :report-file (str (io/file cache "report.edn"))})
            images (into {} (map (juxt :namespace identity)) (:namespace-images report))]
        (doseq [namespace '[aguafria.zig.precompile-dependency-fixture
                            aguafria.zig.precompile-dependent-fixture]]
          (is (= :prepared (:status (images namespace))))
          (is (.isFile (io/file (get-in images [namespace :artifact :library-path]))))))
      (finally (runtime/configure! configuration)))))

(deftest noreturn-preparation-does-not-execute-or-load
  (let [loaded (fn []
                 (into #{}
                       (keep (fn [[module state]] (when (seq (:functions state)) module)))
                       @(var-get (ns-resolve 'aguafria.zig.runtime 'registry))))
        before (loaded)
        fail! (fn [& _] (throw (ex-info "Precompilation invoked a native body" {})))
        report (with-redefs [runtime/invoke! fail!
                             runtime/invoke-with-result! fail!]
                 (a/precompile! {:namespaces ['aguafria.zig.precompile-noreturn-fixture]}))]
    (is (= 3 (count (:functions report))))
    (is (every? #(= :prepared (:status %)) (:functions report)))
    (is (= before (loaded)))
    (is (str/includes? (a/source 'aguafria.zig.precompile-noreturn-fixture)
                       "fn never_run() noreturn"))))

(deftest comptime-result-preparation-does-not-execute-or-load
  (let [loaded (fn []
                 (into #{}
                       (keep (fn [[module state]] (when (seq (:functions state)) module)))
                       @(var-get (ns-resolve 'aguafria.zig.runtime 'registry))))
        before (loaded)
        fail! (fn [& _] (throw (ex-info "Preparation executed a native body" {})))
        types [:u8 [:array 4 :u16] [:error-union :anyerror :i32]]
        report (with-redefs [runtime/invoke! fail! runtime/invoke-with-result! fail!]
                 (a/precompile!
                  {:calls (mapv (fn [type]
                                  {:function 'aguafria.keyword/typeInfo
                                   :args [{:comptime-type type}]})
                                types)}))
        commands (atom [])
        sh shell/sh]
    (is (= 3 (count (:calls report))))
    (is (every? #(= :prepared (:status %)) (:calls report)))
    (is (= before (loaded)))
    (with-redefs [shell/sh (fn [& args]
                             (swap! commands conj (vec (take 2 args)))
                             (apply sh args))]
      (is (= [{:int {:signedness :unsigned :bits 8}}
              {:array {:len 4 :child {:type "u16"} :sentinel_ptr nil}}
              {:error_union {:error_set {:type "anyerror"} :payload {:type "i32"}}}]
             (mapv #(a/value (k/typeInfo %)) types))))
    (is (empty? @commands) (str @commands))))

(deftest unsupported-and-invalid-signatures-fail-explicitly
  (doseq [call '[{:function aguafria.keyword/var :args [:i32]}
                 {:function aguafria.keyword/& :args [:i32]}
                 {:function aguafria.keyword/+ :args [{:comptime 1} {:comptime 2}]}
                 {:function aguafria.keyword/+ :args [:i32]}
                 {:function aguafria.keyword/+ :args [:not-a-type :i32]}]]
    (is (thrown? Exception (a/precompile! {:calls [call]})))))

(defn- fresh-jvm [cache-dir prepare? async?]
  (let [code
        (pr-str
         `(do
            (require '~'[aguafria.zig :as a]
                     '~'[aguafria.keyword :as k]
                     '~'[aguafria.zig.runtime :as runtime]
                     '~'[clojure.java.shell :as shell])
            (aguafria.zig/configure! {:cache-dir ~cache-dir :async? ~async?})
            (let [commands# (atom [])
                  sh# clojure.java.shell/sh
                  report# (with-redefs [clojure.java.shell/sh
                                        (fn [& args#]
                                          (when (= "build-lib" (second args#))
                                            (swap! commands# conj (vec (take 4 args#))))
                                          (apply sh# args#))]
                            (if ~prepare?
                              (with-redefs [aguafria.zig.runtime/invoke!
                                            (fn [& _#] (throw (ex-info "Called native body" {})))
                                            aguafria.zig.runtime/invoke-with-result!
                                            (fn [& _#] (throw (ex-info "Called adapter body" {})))]
                                (aguafria.zig/precompile!
                                 {:namespaces ['aguafria.zig.precompile-fixture
                                               'aguafria.zig.precompile-noreturn-fixture]
                                  :calls [{:function 'aguafria.keyword/+ :args [:i32 :i32]}
                                          {:function 'aguafria.keyword/typeInfo
                                           :args [{:comptime-type :u8}]}
                                          {:function 'aguafria.keyword/typeInfo
                                           :args [{:comptime-type [:array 4 :u16]}]}
                                          {:function 'aguafria.keyword/typeInfo
                                           :args [{:comptime-type [:error-union :anyerror :i32]}]}]
                                  :coercions [:i32]}))
                              (do
                                (assert (= [{:int {:signedness :unsigned :bits 8}}
                                            {:array {:len 4 :child {:type "u16"} :sentinel_ptr nil}}
                                            {:error_union {:error_set {:type "anyerror"}
                                                           :payload {:type "i32"}}}]
                                           (mapv #(aguafria.zig/value (aguafria.keyword/typeInfo %))
                                                 [:u8 [:array 4 :u16] [:error-union :anyerror :i32]])))
                                (require 'aguafria.zig.precompile-fixture
                                         'aguafria.zig.precompile-noreturn-fixture)
                                (doseq [[function# arguments#]
                                        [['aguafria.zig.precompile-noreturn-fixture/direct-panic []]
                                         ['aguafria.zig.precompile-noreturn-fixture/indirect-panic
                                          ["prepared indirect panic"]]]]
                                  (let [failure# (try
                                                   (apply (resolve function#) arguments#)
                                                   nil
                                                   (catch clojure.lang.ExceptionInfo error# error#))]
                                    (assert (= :native-panic
                                               (:aguafria/phase (ex-data failure#))))
                                    (assert (= :execution
                                               (:clojure.error/phase (ex-data failure#))))
                                    (assert (str/ends-with?
                                             (:clojure.error/source (ex-data failure#))
                                             "precompile_noreturn_fixture.clj"))))
                                (assert (= 42 (aguafria.zig/value
                                               ((resolve 'aguafria.zig.precompile-fixture/increment) 41))))
                                (assert (= 42 (aguafria.zig/value
                                               ((resolve 'aguafria.zig.precompile-fixture/subtract) 50 8))))
                                (assert (= 42 (aguafria.zig/value
                                               (aguafria.keyword/+ (aguafria.keyword/i32 20)
                                                                   (aguafria.keyword/i32 22))))))))]
              (prn {:builds (count @commands#)
                    :loaded (count (filter #(seq (:functions %))
                                           (vals @(var-get
                                                   (ns-resolve 'aguafria.zig.runtime
                                                               (symbol "registry"))))))
                    :commands @commands#
                    :calls (:calls report#)
                    :namespace-images (:namespace-images report#)
                    :libraries (into #{}
                                     (comp (filter #(.isFile %))
                                           (filter #(some (fn [suffix#]
                                                            (str/ends-with? (.getName %) suffix#))
                                                          [".dylib" ".so" ".dll"]))
                                           (map str))
                                     (file-seq (io/file ~cache-dir)))
                    :prepared (count (filter #(= :prepared (:status %))
                                             (:functions report#)))}))
            (shutdown-agents)
            (flush)
            (System/exit 0)))
        output (Files/createTempFile "aguafria-precompile-" ".log"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        process (.start (doto (ProcessBuilder.
                               ^java.util.List
                               [(str (System/getProperty "java.home") "/bin/java")
                                "--enable-native-access=ALL-UNNAMED"
                                "-cp" (System/getProperty "java.class.path")
                                "clojure.main" "-e" code])
                          (.redirectErrorStream true)
                          (.redirectOutput (.toFile output))))]
    (when-not (.waitFor process 120 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "Precompilation test JVM timed out" {:log (str output)})))
    (let [text (slurp (.toFile output))]
      (when-not (zero? (.exitValue process))
        (throw (ex-info "Precompilation test JVM failed" {:output text})))
      (edn/read-string (last (str/split-lines text))))))

(defn- declaration-cache-jvm [cache-dir prepare? async? fixture]
  (let [code
        (pr-str
         `(do
            (require '~'[aguafria.zig :as a]
                     '~'[aguafria.zig.runtime :as runtime]
                     '~'[aguafria.zig.jvm :as native-call]
                     '~'[aguafria.zig.explain :as explain]
                     '~'[clojure.java.io :as io])
            (aguafria.zig/configure! {:cache-dir ~cache-dir :async? ~async?})
            (let [events# (atom [])
                  report#
                  (binding [aguafria.zig.explain/*reporter* #(swap! events# conj %)]
                    (if ~prepare?
                      (with-redefs [aguafria.zig.runtime/invoke!
                                    (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))
                                    aguafria.zig.runtime/invoke-with-result!
                                    (fn [& _#] (throw (ex-info "Preparation invoked native code" {})))]
                        (aguafria.zig/precompile!
                         {:namespaces ['~(:namespace fixture)]
                          :report-file ~(str cache-dir "/report.edn")}))
                      (do
                        (require '~(:namespace fixture))
                        (doseq [[type# initial#] '~(:constructors fixture)]
                          (with-open [value# ((resolve type#) initial#)]
                            (assert (= initial# (aguafria.zig/value value#)))))
                        (doseq [[function# arguments# expected#] '~(:calls fixture)
                                typed?# ~(if (:coerce-arguments? fixture) [false true] [false])]
                          (let [declaration# (some #(when (= function# (:qualified-name %)) %)
                                                   (aguafria.zig.runtime/registered-declarations
                                                    (namespace function#)))
                                arguments#
                                (if typed?#
                                  (mapv (fn [index# argument#]
                                          (aguafria.zig.jvm/coerce!
                                           argument#
                                           (aguafria.zig.jvm/argument-reader-type declaration# index#)))
                                        (range (count arguments#)) arguments#)
                                  arguments#)]
                            (try
                              (with-open [value# (apply (resolve function#) arguments#)]
                                (assert (= expected# (aguafria.zig/value value#))))
                              (finally
                                (when typed?# (doseq [argument# arguments#] (aguafria.zig/close! argument#)))))))
                        :checked)))]
              (prn {:cache-dir ~cache-dir
                    :events (frequencies (map :event @events#))
                    :prepared (count (:functions report#))
                    :artifacts (mapv #(select-keys % [:event :module :artifact-key :bundle-id])
                                     (filter :artifact-key @events#))
                    :libraries (into #{}
                                     (comp (filter #(.isFile %))
                                           (filter #(some (fn [suffix#]
                                                            (str/ends-with? (.getName %) suffix#))
                                                          [".dylib" ".so" ".dll"]))
                                           (map str))
                                     (file-seq (io/file ~cache-dir)))}))
            (shutdown-agents)
            (flush)
            (System/exit 0)))
        output (Files/createTempFile "aguafria-lazy-getter-" ".log"
                                     (make-array java.nio.file.attribute.FileAttribute 0))
        process (.start (doto (ProcessBuilder.
                               ^java.util.List
                               [(str (System/getProperty "java.home") "/bin/java")
                                "--enable-native-access=ALL-UNNAMED"
                                "-cp" (System/getProperty "java.class.path")
                                "clojure.main" "-e" code])
                          (.redirectErrorStream true)
                          (.redirectOutput (.toFile output))))]
    (when-not (.waitFor process 120 TimeUnit/SECONDS)
      (.destroyForcibly process)
      (throw (ex-info "Lazy getter test JVM timed out" {:log (str output)})))
    (let [text (slurp (.toFile output))]
      (when-not (zero? (.exitValue process))
        (throw (ex-info "Lazy getter test JVM failed" {:output text :log (str output)})))
      (edn/read-string (last (str/split-lines text))))))

(deftest published-callables-prepare-lazy-and-reachable-getters
  (doseq [async? [false true]]
    (let [cache-dir (str (Files/createTempDirectory "aguafria-lazy-getter-"
                                                    (make-array java.nio.file.attribute.FileAttribute 0)))
          fixture {:namespace 'aguafria.zig.precompile-lazy-getter-fixture
                   :constructors [['aguafria.zig.precompile-lazy-getter-fixture/Box {:value 9}]]
                   :calls [['aguafria.zig.precompile-lazy-getter-fixture/first-value [] 4]
                           ['aguafria.zig.precompile-lazy-getter-fixture/last-value [] 5]
                           ['aguafria.zig.precompile-lazy-getter-fixture/box-value [] {:value 6}]
                           ['aguafria.zig.precompile-lazy-getter-fixture/first-value [] 4]]}
          prepared (declaration-cache-jvm cache-dir true async? fixture)
          restarted (declaration-cache-jvm cache-dir false async? fixture)
          prepared-keys (into #{} (map (juxt :module :artifact-key)) (:artifacts prepared))]
      (is (= 3 (:prepared prepared)))
      (is (pos? (get-in prepared [:events :compiled] 0)))
      (is (zero? (get-in restarted [:events :compiled] 0))
          (str (filterv #(= :compiled (:event %)) (:artifacts restarted))))
      (is (zero? (get-in restarted [:events :compile-failed] 0))
          (str (filterv #(= :compile-failed (:event %)) (:artifacts restarted))))
      (is (seq (:artifacts restarted)) "Actual native lookups must occur")
      (is (every? #(prepared-keys [(:module %) (:artifact-key %)]) (:artifacts restarted))
          (str (remove #(prepared-keys [(:module %) (:artifact-key %)]) (:artifacts restarted))))
      (is (= {:added #{} :removed #{}}
             {:added (set (remove (:libraries prepared) (:libraries restarted)))
              :removed (set (remove (:libraries restarted) (:libraries prepared)))})
          "Fresh ordinary loading and calls must not create additional libraries"))))

(deftest inline-container-parameters-use-the-original-zig-identity
  (doseq [async? [false true]]
    (let [cache-dir (str (Files/createTempDirectory "aguafria-inline-parameter-"
                                                    (make-array java.nio.file.attribute.FileAttribute 0)))
          fixture {:namespace 'aguafria.zig.precompile-inline-parameter-fixture
                   :coerce-arguments? true
                   :calls [['aguafria.zig.precompile-inline-parameter-fixture/public-value [{:value 12}] 12]
                           ['aguafria.zig.precompile-inline-parameter-fixture/public-value [{:value 98}] 98]
                           ['aguafria.zig.precompile-inline-parameter-fixture/private-value [{:value 34}] 34]
                           ['aguafria.zig.precompile-inline-parameter-fixture/option-size
                            [{:string "127.0.0.1" :port_default 3000}] 3009]
                           ['aguafria.zig.precompile-inline-parameter-fixture/option-size
                            [{:string "☔" :port_default 3001}] 3004]
                           ['aguafria.zig.precompile-inline-parameter-fixture/option-size
                            [{:string "" :port_default 3000}] 3000]]}
          prepared (declaration-cache-jvm cache-dir true async? fixture)
          restarted (declaration-cache-jvm cache-dir false async? fixture)
          prepared-keys (into #{} (map (juxt :module :artifact-key)) (:artifacts prepared))]
      (is (= 3 (:prepared prepared)))
      (is (zero? (get-in restarted [:events :compiled] 0))
          (str (filterv #(= :compiled (:event %)) (:artifacts restarted))))
      (is (zero? (get-in restarted [:events :compile-failed] 0)))
      (is (seq (:artifacts restarted)))
      (is (every? #(prepared-keys [(:module %) (:artifact-key %)]) (:artifacts restarted)))
      (is (= (:libraries prepared) (:libraries restarted))))))

(deftest precompilation-persists-across-jvms
  (doseq [async? [false true]]
    (let [parent (io/file ".aguafria/precompile-tests")
          _ (.mkdirs parent)
          cache-dir (str (Files/createTempDirectory
                          (.toPath (.getAbsoluteFile parent)) "cache-"
                          (make-array java.nio.file.attribute.FileAttribute 0)))
          cold (fresh-jvm cache-dir true async?)
          restart (fresh-jvm cache-dir false async?)]
      (is (pos? (:builds cold)))
      (is (zero? (:loaded cold)))
      (is (zero? (:builds restart)) (str restart))
      (is (= (:libraries cold) (:libraries restart))
          "Ordinary require and calls must not create additional native libraries")
      (is (= {'aguafria.zig.precompile-fixture :prepared
              'aguafria.zig.precompile-noreturn-fixture :prepared}
             (into {} (map (juxt :namespace :status))
                   (filter #(contains? #{'aguafria.zig.precompile-fixture
                                         'aguafria.zig.precompile-noreturn-fixture}
                                       (:namespace %))
                           (:namespace-images cold)))))
      (is (= 6 (:prepared cold)))
      (let [checks (mapcat :test-checks (:namespace-images cold))]
        (is (= 1 (count checks)))
        (is (every? #(= :prepared (:status %)) checks)))
      (is (= [{:function 'aguafria.keyword/+ :args [:i32 :i32] :status :prepared}
              {:function 'aguafria.keyword/typeInfo :args [{:comptime-type :u8}] :status :prepared}
              {:function 'aguafria.keyword/typeInfo :args [{:comptime-type [:array 4 :u16]}] :status :prepared}
              {:function 'aguafria.keyword/typeInfo :args [{:comptime-type [:error-union :anyerror :i32]}] :status :prepared}]
             (:calls cold))))))
