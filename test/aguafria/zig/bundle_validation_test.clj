(ns aguafria.zig.bundle-validation-test
  (:require [aguafria.zig.bundle :as bundle]
            [aguafria.zig.discovery :as discovery]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(defn- directory []
  (str (Files/createTempDirectory "aguafria-batch-validation-"
                                  (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- fixture-artifact [cache index]
  (let [file (io/file cache (str "fixture-" index) "module.zig")]
    (io/make-parents file)
    (spit file "export fn __aguafria_probe() i32 { return 42; }\n")
    {:module (str "aguafria.jvm.batch-" index) :hash (str index) :jvm-adapter? true
     :development-panic :shared :development-panic-support-path "support.dylib"
     :validation-pending? true :source-path (str file)
     :command ["zig" "build-lib" "-dynamic" "-femit-bin=unused" "support.dylib"
               "-Osafe" (str "-Mroot=" file)]}))

(deftest queued-artifacts-cannot-be-published
  (let [cache (directory)
        collected (atom {})
        built (atom false)]
    (binding [bundle/*preparing* collected]
      (bundle/observe! (fixture-artifact cache 0))
      (with-redefs-fn {#'bundle/build-pack! (fn [& _] (reset! built true))}
        #(is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires validated artifacts"
                               (bundle/finish! cache collected {})))))
    (is (false? @built))))

(deftest successful-artifacts-use-one-compiler-batch
  (let [cache (directory)
        collected (atom {})
        commands (atom [])]
    (binding [bundle/*preparing* collected]
      (doseq [index (range 64)] (bundle/observe! (fixture-artifact cache index)))
      (let [result (bundle/validate-pending!
                    cache collected
                    {:run-command (fn [command _]
                                    (swap! commands conj command) {:exit 0 :out "" :err ""})
                     :validate-artifact (fn [_] (throw (ex-info "Unexpected individual validation" {})))})]
        (is (= 64 (:candidates result)))
        (is (= 1 (:compiler-batches result)))
        (is (= {:validated 64} (:statuses result)))
        (is (zero? (:individual-rechecks result)))
        (is (every? (comp false? :validation-pending?) (vals (:artifacts @collected))))))
    (is (= 1 (count @commands)))
    (let [arguments (mapv edn/read-string
                          (str/split-lines (slurp (subs (last (first @commands)) 1))))]
      (is (some #{"-fno-emit-bin"} arguments))
      (is (not-any? #(str/starts-with? % "-femit-bin=") arguments)))
    (is (not (.exists (io/file cache "bundles" "index"))))))

(deftest preparation-records-remain-pending-until-validation
  (let [cache (directory)
        collected (atom {})
        artifact (fixture-artifact cache 0)]
    (binding [bundle/*preparing* collected bundle/*batch-validation?* true]
      (let [report (bundle/call-with-validation-scope
                    #(do (bundle/observe! artifact) {:status :prepared :function 'fixture}))]
        (is (= :pending-validation (:status report)))
        (is (integer? (:validation-scope report)))
        (bundle/validate-pending! cache collected
                                  {:run-command (fn [& _] {:exit 0})})
        (let [resolved (bundle/resolve-validation-report! collected [report] discovery/error-report)]
          (is (= [{:status :prepared :function 'fixture}] resolved)))))))

(deftest concurrent-identical-batches-share-a-locked-source-directory
  (let [cache (directory)
        artifact (fixture-artifact cache 0)
        active (atom 0)
        maximum (atom 0)
        validate (fn []
                   (binding [bundle/*preparing* (atom {})]
                     (bundle/observe! artifact)
                     (bundle/validate-pending!
                      cache bundle/*preparing*
                      {:run-command (fn [& _]
                                      (swap! maximum max (swap! active inc))
                                      (try
                                        (Thread/sleep 20)
                                        {:exit 0}
                                        (finally (swap! active dec))))})))
        results (mapv deref [(future (validate)) (future (validate))])]
    (is (= 1 @maximum))
    (is (every? #(= {:validated 1} (:statuses %)) results))
    (is (not (.exists (io/file cache "bundles" "index"))))))

(deftest rejected-scopes-retry-once-and-retain-original-diagnostics
  (let [collected (atom {})
        attempts (atom 0)
        artifact {:module "fixture" :hash "invalid" :validation-pending? true}]
    (binding [bundle/*preparing* collected bundle/*batch-validation?* true]
      (let [prepare (fn []
                      (swap! attempts inc)
                      (if bundle/*batch-validation?*
                        (do (bundle/observe! artifact) {:status :prepared :function 'fixture})
                        (throw (ex-info "Original compiler diagnostic" {:reason :fixture-failure}))))
            record (bundle/call-with-validation-scope prepare)
            resolved (bundle/resolve-validation-report! collected [record record] discovery/error-report)]
        (is (= 2 @attempts))
        (is (every? #(= :failed (:status %)) resolved))
        (is (every? #(= "Original compiler diagnostic" (:message %)) resolved))
        (is (every? #(= 'fixture (:function %)) resolved))))))

(deftest real-compiler-isolates-one-invalid-handler
  (let [configuration (runtime/configuration)
        cache (directory)
        collected (atom {})
        compile! (fn [index source]
                   (let [module (str "aguafria.jvm.batch-real-" index)]
                     (#'runtime/compile-source!
                      module source [{:module module :kind :raw :name 'fixture
                                      :value source :public? false :jvm-adapter? true}])
                     {:function (symbol module "fixture") :status :prepared}))]
    (try
      (runtime/configure! {:cache-dir cache})
      (binding [runtime/*compile-only?* true bundle/*preparing* collected
                bundle/*batch-validation?* true]
        (let [reports (mapv (fn [[index source]]
                              (bundle/call-with-validation-scope #(compile! index source)))
                            [[0 "export fn __aguafria_probe() i32 { return 42; }"]
                             [1 "export fn __aguafria_probe(a: i32) i32 { return a / 2; }"]
                             [2 "export fn __aguafria_probe() i32 { return 43; }"]])]
          (is (= [:pending-validation :pending-validation :pending-validation]
                 (mapv :status reports)))
          (let [validation (runtime/validate-precompile-bundles! collected)
                resolved (bundle/resolve-validation-report! collected reports discovery/error-report)]
            (is (= {:validated 2 :failed 1} (:statuses validation)))
            (is (= 1 (:individual-rechecks validation)))
            (is (= [:prepared :failed :prepared] (mapv :status resolved)))
            (is (str/includes? (:stderr (second resolved)) "signed integers must use"))
            (is (some #(str/includes? % "signed integers must use") (:causes (second resolved))))
            (is (= 2 (count (:artifacts @collected))))
            (is (not (.exists (io/file cache "bundles" "index"))))
            (let [pack (runtime/finish-precompile-bundles! collected)]
              (is (= 2 (:packed-handlers pack)))
              (is (= 1 (count (:packs pack))))))))
      (finally (runtime/configure! configuration)))))

(deftest batching-preserves-the-ordinary-artifact-identity
  (let [configuration (runtime/configuration)
        cache (directory)
        module "aguafria.jvm.batch-identity"
        source "export fn __aguafria_probe() i32 { return 42; }"
        declarations [{:module module :kind :raw :name 'fixture
                       :value source :public? false :jvm-adapter? true}]
        prepare (fn [batch?]
                  (let [collected (atom {})
                        artifact (binding [runtime/*compile-only?* true
                                           bundle/*preparing* collected
                                           bundle/*batch-validation?* batch?]
                                   (bundle/call-with-validation-scope
                                    #(let [artifact (#'runtime/compile-source! module source declarations)]
                                       {:status :prepared :artifact artifact})))]
                    {:artifact (:artifact artifact) :collected collected}))]
    (try
      (runtime/configure! {:cache-dir cache})
      (let [ordinary (prepare false)
            batch (prepare true)
            keys [:module :hash :command :compiled-source :source-path]]
        (is (= (select-keys (:artifact ordinary) keys)
               (select-keys (:artifact batch) keys)))
        (is (false? (:validation-pending? (:artifact ordinary))))
        (is (true? (:validation-pending? (:artifact batch))))
        (binding [runtime/*compile-only?* true bundle/*preparing* (:collected batch)]
          (is (= {:validated 1}
                 (:statuses (runtime/validate-precompile-bundles! (:collected batch)))))))
      (finally (runtime/configure! configuration)))))
