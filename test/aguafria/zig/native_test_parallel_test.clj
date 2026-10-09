(ns aguafria.zig.native-test-parallel-test
  (:require [aguafria.zig :as a]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.util.concurrent Callable CountDownLatch Executors TimeUnit]))

(defn- await! [latch]
  (assert (.await ^CountDownLatch latch 30 TimeUnit/SECONDS) "Worker deadline exceeded"))

(deftest scheduler-bounds-outstanding-work-and-captures-configuration
  (let [original (runtime/configuration)
        started (CountDownLatch. 1)
        release (CountDownLatch. 1)
        submitting (CountDownLatch. 1)
        accepted (CountDownLatch. 1)]
    (with-open [executor (Executors/newFixedThreadPool 1 (.factory (Thread/ofVirtual)))
                caller (Executors/newFixedThreadPool 1 (.factory (Thread/ofVirtual)))]
      (try
        (binding [runtime/*compile-only?* true]
          (let [submit (#'precompile/build-submitter executor 1)
                work (fn [] (.countDown started) (await! release)
                       (:cache-dir (runtime/configuration)))
                first-job (submit work)
                _ (await! started)
                second-job (submit work)
                third-job (.submit caller ^Callable
                                   (bound-fn []
                                     (.countDown submitting)
                                     (let [job (submit work)]
                                       (.countDown accepted)
                                       job)))]
            (await! submitting)
            (is (not (.await accepted 100 TimeUnit/MILLISECONDS))
                "Only two outstanding jobs are allowed for one worker")
            (runtime/configure! {:cache-dir (str (io/file (:cache-dir original) "later"))})
            (.countDown release)
            (is (= (:cache-dir original) (.get first-job)))
            (is (= (:cache-dir original) (.get second-job)))
            (is (.get (.get third-job)))
            (is (= 0 (.getCount accepted)))))
        (finally
          (.countDown release)
          (runtime/configure! original))))))

(deftest frozen-tests-retain-registration-time-errors-and-artifact-identity
  (let [namespace (create-ns (gensym "aguafria.parallel-test-fixture-"))
        images (atom {})
        release (CountDownLatch. 1)]
    (with-open [executor (Executors/newFixedThreadPool 2 (.factory (Thread/ofVirtual)))]
      (try
        (binding [*ns* namespace
                  runtime/*compile-only?* true
                  runtime/*source-only-registration?* true
                  runtime/*prepared-namespace-images* images
                  runtime/*precompile-build-submitter*
                  (fn [build]
                    (.submit executor ^Callable
                             (bound-fn [] (await! release) (build))))]
          (refer 'clojure.core)
          (require '[aguafria.zig :as a] '[aguafria.keyword :as k]
                   '[aguafria.std.debug :as debug])
          (eval '(a/defconst expected :u8 0))
          (eval '(a/deftest before-edit
                   (k/comptime (debug/assert (k/== expected 1)))))
          (eval '(a/defconst expected :u8 1))
          (eval '(a/deftest after-edit
                   (k/comptime (debug/assert (k/== expected 1))))))
        (let [checks (get-in @images [(str (ns-name namespace)) :test-checks])]
          (is (= [:pending :pending] (mapv :status checks)))
          (.countDown release)
          (let [[bad good] (mapv #(runtime/resolve-precompiled-test!
                                   % discovery/error-report) checks)
                plan (#'runtime/native-test-plan (str (ns-name namespace)) 'after-edit)]
            (is (= :failed (:status bad)))
            (is (= :zig-test (get-in bad [:details :aguafria/phase])))
            (is (seq (get-in bad [:details :diagnostics])))
            (is (= :prepared (:status good)))
            (is (= (get-in good [:artifact :library-path])
                   (get-in plan [:details :library-path])))
            (is (not (:native-test-job good)))
            (is (str/includes? (slurp (get-in good [:artifact :source-path])) "= 1;"))
            (is (thrown? clojure.lang.Compiler$CompilerException
                         (binding [*ns* namespace]
                           (eval '(a/deftest rejected-normal
                                    (k/= :_ (k/as 256 :u8)))))))
            (is (not (.hasRoot ^clojure.lang.Var (ns-resolve namespace 'rejected-normal))))
            (is (not-any? #(= 'rejected-normal (:name %))
                          (runtime/registered-declarations (str (ns-name namespace)))))))
        (finally
          (.countDown release)
          (remove-ns (ns-name namespace)))))))

(deftest failed-owner-builds-are-resolved-not-counted-as-prepared
  (with-open [executor (Executors/newFixedThreadPool 1 (.factory (Thread/ofVirtual)))]
    (let [job (.submit executor ^Callable
                       (fn [] (throw (ex-info "compiler rejected owner" {:aguafria/phase :zig-test}))))
          result (runtime/resolve-precompiled-test!
                  {:test 'fixture/owner :status :pending :native-test-job job}
                  discovery/error-report)]
      (is (= :failed (:status result)))
      (is (= 'fixture/owner (:test result)))
      (is (not (:native-test-job result)))
      (is (= :prepared (:status (runtime/resolve-precompiled-test!
                                 {:test 'fixture/owner :status :prepared}
                                 discovery/error-report)))))))

(deftest mutable-compiler-inputs-stay-synchronous
  (let [test 'fixture/owner
        artifact {:library-path "/cache/owner.dylib" :source-path "/cache/owner.zig"}]
    (binding [runtime/*precompile-build-submitter* (fn [& _] (throw (ex-info "Unsafe queue" {})))]
      (with-redefs-fn {#'runtime/native-test-plan (fn [& _] {:parallel-safe? false})
                       #'runtime/build-native-test-plan! (fn [_] artifact)}
        #(is (= {:test test :status :prepared :artifact artifact}
                (runtime/precompile-test! test)))))))

(deftest same-artifact-jobs-share-one-compiler-build
  (let [started (CountDownLatch. 1)
        release (CountDownLatch. 1)
        commands (atom [])
        built? (atom false)
        directory (io/file (System/getProperty "java.io.tmpdir"))
        library (io/file directory (str "aguafria-parallel-" (random-uuid) ".dylib"))
        details {:command ["compile"] :link-command ["link"] :source-path "owner.zig"}
        plan {:module "fixture.owner" :source "" :directory directory
              :library-file library :details details}]
    (with-open [executor (Executors/newFixedThreadPool 2 (.factory (Thread/ofVirtual)))]
      (with-redefs-fn
        {#'runtime/build-development-panic-support! (fn [_])
         #'runtime/usable-native-artifact? (fn [& _] @built?)
         #'runtime/preserve-native-debug! (fn [& _])
         #'runtime/run-command
         (fn [command _]
           (swap! commands conj command)
           (when (= ["compile"] command)
             (.countDown started)
             (await! release))
           (when (= ["link"] command) (reset! built? true))
           {:exit 0 :out "" :err ""})}
        (fn []
          (try
            (let [first-job (.submit executor ^Callable (bound-fn [] (#'runtime/build-native-test-plan! plan)))
                  _ (await! started)
                  second-job (.submit executor ^Callable (bound-fn [] (#'runtime/build-native-test-plan! plan)))]
              (.countDown release)
              (is (= details (.get first-job) (.get second-job)))
              (is (= [["compile"] ["link"]] @commands)))
            (finally (.countDown release))))))))

(deftest namespace-load-errors-do-not-hide-submitted-test-diagnostics
  (let [check {:test 'fixture/owner :status :failed :error "original compiler failure"}
        images (atom {})]
    (binding [runtime/*prepared-namespace-images* (atom {"fixture" {:test-checks [check]}})]
      (#'precompile/resolve-precompile-images! images))
    (is (= [check] (get-in @images ["fixture" :test-checks])))
    (is (= :failed (get-in @images ["fixture" :status])))))
