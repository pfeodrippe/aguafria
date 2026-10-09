(ns aguafria.zig.compiler-work-test
  (:require [aguafria.zig.compiler-work :as work]
            [aguafria.zig.emitter :as emitter]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]
           [java.util.concurrent Callable Executors]))

(defn- command! [action exit]
  (work/run-command! ["zig" action] #(hash-map :exit exit)))

(deftest ordinary-command-semantics-are-unchanged
  (let [calls (atom 0)
        result {:exit 1 :out "output" :err "diagnostic"}]
    (is (identical? result
                    (work/run-command! ["zig" "build-lib"]
                                       #(do (swap! calls inc) result))))
    (is (= 1 @calls))))

(deftest counts-rejected-builds-and-separates-metadata-and-debug-tools
  (let [collector (work/collector)]
    (binding [work/*collector* collector]
      (binding [work/*phase* :type-query] (command! "test" 1))
      (binding [work/*phase* :handler-bundle] (command! "build-lib" 0))
      (command! "version" 0)
      (work/run-command! ["dsymutil" "--flat" "library"] #(hash-map :exit 0)))
    (let [report (work/report collector)]
      (is (= 2 (:compiler-invocations report)))
      (is (false? (:one-compilation? report)))
      (is (= 1 (:metadata-commands report)))
      (is (= 1 (:debug-information-commands report)))
      (is (= 1 (get-in report [:by-phase :type-query :compiler "test"])))
      (is (= 1 (get-in report [:by-phase :handler-bundle :compiler "build-lib"])))
      (is (= [1 0 0 0] (mapv :exit (:command-samples report)))))))

(deftest spawn-errors-remain-errors-and-count-as-attempts
  (let [collector (work/collector)
        failure (java.io.IOException. "Missing compiler")]
    (binding [work/*collector* collector]
      (is (identical? failure
                      (try (work/run-command! ["zig" "build-lib"] #(throw failure))
                           (catch java.io.IOException error error)))))
    (let [report (work/report collector)]
      (is (= 1 (:compiler-invocations report)))
      (is (= "class java.io.IOException"
             (get-in report [:command-samples 0 :spawn-error]))))))

(deftest builtin-metadata-is-not-a-compiler-build
  (let [collector (work/collector)]
    (binding [work/*collector* collector]
      (work/run-command! ["zig" "build-lib" "--show-builtin"] #(hash-map :exit 0))
      (command! "env" 0))
    (let [report (work/report collector)]
      (is (zero? (:compiler-invocations report)))
      (is (true? (:one-compilation? report)))
      (is (= 2 (:metadata-commands report))))))

(deftest concurrent-workers-keep-exact-counts-with-bounded-samples
  (let [collector (work/collector)]
    (binding [work/*collector* collector]
      (with-open [executor (Executors/newFixedThreadPool 4 (.factory (Thread/ofVirtual)))]
        (let [jobs (mapv (fn [_]
                           (.submit executor ^Callable
                                    (bound-fn []
                                      (dotimes [_ 100] (command! "build-lib" 0)))))
                         (range 4))]
          (doseq [job jobs] (.get ^java.util.concurrent.Future job)))))
    (let [report (work/report collector)]
      (is (= 400 (:compiler-invocations report)))
      (is (= 400 (get-in report [:by-phase :source-build :compiler "build-lib"])))
      (is (= 32 (count (:command-samples report))))
      (is (= 368 (:omitted-command-samples report)))
      (is (false? (:one-compilation? report))))))

(deftest reports-count-the-whole-preparation-not-only-the-pack
  (let [directory (Files/createTempDirectory
                   "aguafria-compiler-accounting-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        file (str (io/file (str directory) "report.edn"))]
    (with-redefs [jvm/precompile-coercion!
                  (fn [type]
                    (binding [work/*phase* :type-query] (command! "test" 1))
                    {:type type :status :prepared})
                  runtime/finish-precompile-bundles!
                  (fn [& _]
                    (command! "build-lib" 0)
                    {:compiler-invocations 1 :packs [{:id "fixture"}]})]
      (let [report (precompile/precompile! {:coercions [:i32] :report-file file})
            persisted (edn/read-string (slurp file))]
        (is (= 1 (get-in report [:bundles :compiler-invocations])))
        (is (= 2 (get-in report [:compiler-work :compiler-invocations])))
        (is (false? (get-in report [:compiler-work :one-compilation?])))
        (is (= (:compiler-work report) (:compiler-work persisted)))))))

(deftest failed-preparation-retains-complete-compiler-accounting
  (let [directory (Files/createTempDirectory
                   "aguafria-compiler-accounting-failed-"
                   (make-array java.nio.file.attribute.FileAttribute 0))
        file (str (io/file (str directory) "report.edn"))]
    (with-redefs [jvm/precompile-coercion! (fn [type] {:type type :status :prepared})
                  runtime/finish-precompile-bundles!
                  (fn [& _]
                    (command! "build-lib" 1)
                    (throw (ex-info "Rejected bundle" {:aguafria/phase :bundle-compile :exit 1})))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (precompile/precompile! {:coercions [:i32] :report-file file})))
      (let [report (edn/read-string (slurp file))]
        (is (= :failed (get-in report [:bundles :status])))
        (is (= 1 (get-in report [:compiler-work :compiler-invocations])))
        (is (= 1 (get-in report [:compiler-work :command-samples 0 :exit])))))))

(deftest compiler-type-inspection-is-counted-without-running-the-body
  (binding [runtime/*source-only-registration?* true]
    (require 'aguafria.zig.bundle-single-pass-fixture))
  (let [collector (work/collector)
        result
        (binding [work/*collector* collector]
          (runtime/inspect-module!
           'aguafria.zig.bundle-single-pass-fixture
           (fn [declarations]
             {:source (str (emitter/emit-module "aguafria.zig.bundle-single-pass-fixture"
                                                declarations)
                           "\ncomptime { _ = &shift; }\n")})))]
    (is (zero? (:exit result)))
    (is (= 1 (:compiler-invocations (work/report collector))))
    (is (= 1 (get-in (work/report collector) [:by-phase :type-query :compiler "test"])))
    (is (true? (:one-compilation? (work/report collector))))))
