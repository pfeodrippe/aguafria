(ns aguafria.zig.bundle-fused-validation-test
  (:require [aguafria.zig.bundle :as bundle]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.lang.foreign FunctionDescriptor Linker MemoryLayout ValueLayout]
           [java.nio.file Files]
           [java.util ArrayList]))

(defn- directory []
  (str (Files/createTempDirectory "aguafria-fused-validation-"
                                  (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- prepare! [index source]
  (let [module (str "aguafria.jvm.fused-" index)
        compiled (#'runtime/compile-source!
                  module source [{:module module :kind :raw :name 'fixture
                                  :value source :public? false :jvm-adapter? true}])]
    {:function (symbol module "fixture") :status :prepared :artifact compiled}))

(defn- valid-source [number]
  (str "export fn __aguafria_probe() i32 { return " number "; }"))

(defn- probe [entry]
  (let [address (.orElseThrow (.find (bundle/symbol-lookup entry) "__aguafria_probe"))
        handle (.downcallHandle (Linker/nativeLinker) address
                                (FunctionDescriptor/of ValueLayout/JAVA_INT
                                                       (make-array MemoryLayout 0))
                                (make-array java.lang.foreign.Linker$Option 0))]
    (.invokeWithArguments handle (ArrayList.))))

(deftest full-codegen-validates-more-than-one-batch-before-publication
  (let [configuration (runtime/configuration)
        cache (directory)
        collected (atom {})
        commands (atom [])
        first-publication (atom nil)
        publish #'bundle/publish-edn!
        original-publish @publish
        original-sh shell/sh]
    (try
      (runtime/configure! {:cache-dir cache})
      (binding [runtime/*compile-only?* true bundle/*preparing* collected
                bundle/*batch-validation?* true]
        (let [records (mapv #(bundle/call-with-validation-scope
                              (fn [] (prepare! % (valid-source %)))) (range 260))
              result
              (with-redefs-fn
                {#'shell/sh (fn [& arguments]
                              (when (= "build-lib" (second arguments))
                                (is (every? :validation-pending? (vals (:artifacts @collected))))
                                (is (not (.exists (io/file cache "bundles" "index"))))
                                (swap! commands conj arguments))
                              (apply original-sh arguments))
                 publish (fn [path value]
                           (when (nil? @first-publication)
                             (reset! first-publication
                                     (select-keys @collected [:validation :validation-results])))
                           (original-publish path value))}
                #(runtime/finish-precompile-bundles! collected {:validate-pending? true}))
              resolved (bundle/resolve-validation-report! collected records discovery/error-report)]
          (is (= 1 (count @commands)))
          (is (= 260 (:packed-handlers result)))
          (is (= {:validated 260} (get-in result [:validation :statuses])))
          (is (= 1 (get-in result [:validation :compiler-batches])))
          (is (= :compile-and-link (get-in result [:validation :mode])))
          (is (= {:validated 260} (get-in @first-publication [:validation :statuses])))
          (is (= 260 (count (:validation-results @first-publication))))
          (is (every? #(= :prepared (:status %)) resolved))
          (is (every? (comp false? :validation-pending?) (vals (:artifacts @collected))))
          (is (= 0 (probe (bundle/find-artifact cache (:artifact (first records))))))
          (is (= 259 (probe (bundle/find-artifact cache (:artifact (last records))))))))
      (finally (runtime/configure! configuration)))))

(deftest failed-full-build-cannot-publish-pending-handlers
  (let [configuration (runtime/configuration)
        cache (directory)
        collected (atom {})]
    (try
      (runtime/configure! {:cache-dir cache})
      (binding [runtime/*compile-only?* true bundle/*preparing* collected
                bundle/*batch-validation?* true]
        (let [records (mapv (fn [[index source]]
                              (bundle/call-with-validation-scope #(prepare! index source)))
                            [[0 (valid-source 7)]
                             [1 "export fn __aguafria_probe(a: i32) i32 { return a / 2; }"]
                             [2 (valid-source 9)]])]
          (is (thrown? clojure.lang.ExceptionInfo
                       (runtime/finish-precompile-bundles! collected {:validate-pending? true})))
          (is (not (.exists (io/file cache "bundles" "index"))))
          (is (every? :validation-pending? (vals (:artifacts @collected))))
          (is (nil? (:validation-results @collected)))
          (runtime/validate-precompile-bundles! collected)
          (let [resolved (bundle/resolve-validation-report! collected records discovery/error-report)
                result (runtime/finish-precompile-bundles! collected)]
            (is (= [:prepared :failed :prepared] (mapv :status resolved)))
            (is (str/includes? (:stderr (second resolved)) "signed integers must use"))
            (is (= 2 (:packed-handlers result)))
            (is (nil? (bundle/find-artifact cache (:artifact (second records))))))))
      (finally (runtime/configure! configuration)))))

(deftest cached-pack-proves-the-same-original-artifact-identities
  (let [configuration (runtime/configuration)
        cache (directory)
        collected (atom {})]
    (try
      (runtime/configure! {:cache-dir cache})
      (binding [runtime/*compile-only?* true bundle/*preparing* collected
                bundle/*batch-validation?* true]
        (let [records (mapv #(bundle/call-with-validation-scope
                              (fn [] (prepare! % (valid-source %)))) (range 2))
              original-artifacts (:artifacts @collected)
              _ (runtime/validate-precompile-bundles! collected)
              ordinary (runtime/finish-precompile-bundles! collected)
              _ (swap! collected assoc :artifacts original-artifacts :validation-results {})
              fused (runtime/finish-precompile-bundles! collected {:validate-pending? true})]
          (is (= (mapv :id (:packs ordinary)) (mapv :id (:packs fused))))
          (is (true? (get-in fused [:packs 0 :cached?])))
          (is (zero? (get-in fused [:validation :compiler-batches])))
          (is (= {:validated 2} (get-in fused [:validation :statuses])))
          (doseq [record records]
            (is (= (get-in ordinary [:packs 0 :id])
                   (:id (bundle/find-artifact cache (:artifact record))))))))
      (finally (runtime/configure! configuration)))))

(deftest precompile-aborts-after-one-rejected-bundle-compilation
  (let [configuration (runtime/configuration)
        cache (directory)
        report-file (str (io/file cache "report.edn"))
        builds (atom [])
        original-sh shell/sh
        sources {:i32 (valid-source 42)
                 :u8 "export fn __aguafria_probe(a: i32) i32 { return a / 2; }"
                 :i64 (valid-source 43)}]
    (try
      (runtime/configure! {:cache-dir cache})
      (let [failure
            (with-redefs [jvm/precompile-coercion!
                          (fn [type] (assoc (prepare! (name type) (sources type)) :type type))
                          shell/sh (fn [& arguments]
                                     (when (and (= "build-lib" (second arguments))
                                                (str/starts-with? (nth arguments 2 "") "@"))
                                       (swap! builds conj arguments))
                                     (apply original-sh arguments))]
              (try (precompile/precompile!
                    {:coercions [:i32 :u8 :i64] :report-file report-file})
                   nil
                   (catch clojure.lang.ExceptionInfo error error)))
            report (edn/read-string (slurp report-file))]
        (is (= :bundle-compile (:aguafria/phase (ex-data failure))))
        (is (= 1 (count @builds)))
        (is (str/includes? (:err (ex-data failure)) "signed integers must use"))
        (is (= [:pending-validation :pending-validation :pending-validation]
               (mapv :status (:coercions report))))
        (is (= :failed (get-in report [:bundles :status])))
        (is (= 1 (get-in report [:compiler-work :by-phase :handler-bundle :compiler "build-lib"])))
        (is (str/includes? (get-in report [:bundles :err]) "signed integers must use"))
        (is (not (.exists (io/file cache "bundles" "index"))))
        (is (not (.exists (io/file cache "validation")))))
      (finally (runtime/configure! configuration)))))

(deftest native-wrappers-and-linked-adapters-share-one-handler-compilation
  (let [configuration (runtime/configuration)
        cache (directory)
        collected (atom {})
        commands (atom [])
        original-run @#'runtime/run-command]
    (try
      (runtime/configure! {:cache-dir cache})
      (binding [runtime/*compile-only?* true
                runtime/*source-only-registration?* true
                runtime/*prepared-namespace-images* (atom {})
                bundle/*preparing* collected
                bundle/*batch-validation?* true]
        (require 'aguafria.zig.bundle-single-pass-fixture :reload)
        (with-redefs-fn
          {#'runtime/run-command
           (fn [command directory]
             (when (= "build-lib" (second command))
               (swap! commands conj command))
             (original-run command directory))}
          (fn []
            (let [image-record (runtime/precompile-namespace-load!
                                'aguafria.zig.bundle-single-pass-fixture)
                  type-record (runtime/precompile-type!
                               'aguafria.zig.bundle-single-pass-fixture/Point)
                  function-record (bundle/call-with-validation-scope
                                   #(runtime/precompile-function!
                                     'aguafria.zig.bundle-single-pass-fixture/shift))
                  external-record (bundle/call-with-validation-scope
                                   #(prepare! "external"
                                              (str "extern fn unused_external() i32;\n"
                                                   (valid-source 7))))
                  artifacts (vals (:artifacts @collected))
                  support-builds (filter #(str/includes? (str %) "development-support") @commands)]
              (is (= :pending-validation (:status type-record)))
              (is (= :pending-validation (:status image-record)))
              (is (= :pending-validation (:status function-record)))
              (is (= :pending-validation (:status external-record)))
              (is (some #(and (:jvm-wrapper? %) (not (:jvm-adapter? %))) artifacts))
              (is (some :jvm-namespace-image? artifacts))
              (is (:requires-link-validation? (bundle/candidate (:artifact external-record))))
              (is (every? :validation-pending? artifacts))
              (is (= (count support-builds) (count @commands)))
              (let [result (runtime/finish-precompile-bundles! collected {:validate-pending? true})
                    reports (bundle/resolve-validation-report!
                             collected [image-record type-record function-record external-record]
                             discovery/error-report)]
                (is (= 1 (- (count @commands) (count support-builds))))
                (is (= 1 (:compiler-invocations result)))
                (is (= [:prepared :prepared :prepared :prepared] (mapv :status reports)))
                (is (= (count artifacts) (:packed-handlers result)))
                (is (empty? (:standalone result)))
                (is (every? #(bundle/find-artifact cache %) artifacts))
                (is (= 7 (probe (bundle/find-artifact cache (:artifact external-record))))))))))
      (with-redefs-fn
        {#'runtime/run-command
         (fn [command directory]
           (when (= "build-lib" (second command))
             (throw (ex-info "Prepared consumer compiled a new library" {:command command})))
           (original-run command directory))}
        (fn []
          (let [point-var (requiring-resolve 'aguafria.zig.bundle-single-pass-fixture/Point)
                shift-var (requiring-resolve 'aguafria.zig.bundle-single-pass-fixture/shift)
                point (point-var {:x 1 :y 2})
                shifted (shift-var point 3)]
            (is (= 4 (long @(:x shifted))))
            (is (= 2 (long @(:y shifted)))))))
      (finally (runtime/configure! configuration)))))

(deftest missing-external-symbol-fails-the-single-build-without-publication
  (let [configuration (runtime/configuration)
        cache (directory)
        report-file (str (io/file cache "report.edn"))
        commands (atom [])
        original-run @#'runtime/run-command]
    (try
      (runtime/configure! {:cache-dir cache})
      (let [failure
            (with-redefs-fn
              {#'jvm/precompile-coercion!
               (fn [_]
                 (prepare! "missing-external"
                           (str "extern fn aguafria_missing_external(x: i32) i32;\n"
                                "export fn __aguafria_probe(x: i32) i32 {\n"
                                "    return aguafria_missing_external(x);\n"
                                "}\n")))
               #'runtime/run-command
               (fn [command directory]
                 (when (and (= "build-lib" (second command))
                            (str/starts-with? (nth command 2 "") "@"))
                   (swap! commands conj command))
                 (original-run command directory))}
              #(try (precompile/precompile! {:coercions [:i32] :report-file report-file})
                    nil
                    (catch clojure.lang.ExceptionInfo error error)))
            report (edn/read-string (slurp report-file))]
        (is (= 1 (count @commands)))
        (is (= :bundle-compile (:aguafria/phase (ex-data failure))))
        (is (str/includes? (:err (ex-data failure)) "aguafria_missing_external"))
        (is (str/includes? (:err (ex-data failure)) "undefined symbol"))
        (is (= :pending-validation (get-in report [:coercions 0 :status])))
        (is (= :failed (get-in report [:bundles :status])))
        (is (not (.exists (io/file cache "bundles" "index"))))
        (is (not (.exists (io/file cache "validation")))))
      (finally (runtime/configure! configuration)))))

(deftest unsupported-handler-does-not-fall-back-to-a-standalone-build
  (let [configuration (runtime/configuration)
        cache (directory)
        collected (atom {})
        commands (atom [])
        original-run @#'runtime/run-command]
    (try
      (runtime/configure! {:cache-dir cache :development-panic :full})
      (binding [runtime/*compile-only?* true bundle/*preparing* collected
                bundle/*batch-validation?* true]
        (let [failure
              (with-redefs-fn
                {#'runtime/run-command
                 (fn [command directory]
                   (when (and (= "build-lib" (second command))
                              (not (str/includes? (str command) "development-support")))
                     (swap! commands conj command))
                   (original-run command directory))}
                #(try (bundle/call-with-validation-scope
                       (fn [] (prepare! "unsupported-panic" (valid-source 7))))
                      nil
                      (catch clojure.lang.ExceptionInfo error error)))]
          (is (= :unsupported-bundle-handler (:reason (ex-data failure))))
          (is (= :adapter-planning (:aguafria/phase (ex-data failure))))
          (is (empty? @commands))
          (is (empty? (:artifacts @collected)))
          (is (not (.exists (io/file cache "bundles" "index"))))))
      (finally (runtime/configure! configuration)))))

(deftest changed-validation-inputs-cannot-be-published
  (let [configuration (runtime/configuration)
        cache (directory)
        collected (atom {})
        original-sh shell/sh]
    (try
      (runtime/configure! {:cache-dir cache})
      (binding [runtime/*compile-only?* true bundle/*preparing* collected
                bundle/*batch-validation?* true]
        (bundle/call-with-validation-scope #(prepare! 0 (valid-source 42)))
        (let [late (binding [bundle/*preparing* (atom {})]
                     (:artifact (bundle/call-with-validation-scope
                                 #(prepare! 1 (valid-source 43)))))]
          (with-redefs [shell/sh (fn [& arguments]
                                   (let [result (apply original-sh arguments)]
                                     (when (= "build-lib" (second arguments))
                                       (bundle/observe! late))
                                     result))]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo #"inputs changed during compilation"
                 (runtime/finish-precompile-bundles! collected
                                                     {:validate-pending? true}))))
          (is (not (.exists (io/file cache "bundles" "index"))))
          (is (not-any? #(= "manifest.edn" (.getName ^java.io.File %))
                        (file-seq (io/file cache "bundles"))))
          (is (= true (every? :validation-pending? (vals (:artifacts @collected)))))
          (is (nil? (:validation-results @collected)))))
      (finally (runtime/configure! configuration)))))
