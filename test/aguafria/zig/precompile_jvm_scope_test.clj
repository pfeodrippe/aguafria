(ns aguafria.zig.precompile-jvm-scope-test
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.compiler-work :as work]
            [aguafria.zig.precompile :as precompile]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Files]))

(deftest preparation-needs-no-new-mode-setting
  (is (nil? (#'precompile/validate-options! {:coercions [:i32]})))
  (is (thrown? clojure.lang.ExceptionInfo
               (#'precompile/validate-options! {:coercions [:i32] :native-tests :on-demand}))))

(deftest test-body-jvm-handlers-are-prepared-without-native-test-artifacts
  (let [configuration (runtime/configuration)
        directory (str (Files/createTempDirectory "aguafria-precompile-jvm-scope-"
                                                  (make-array java.nio.file.attribute.FileAttribute 0)))
        fixture 'aguafria.zig.precompile-jvm-scope-fixture
        forbidden (fn [& _] (throw (ex-info "Preparation executed native code" {})))]
    (try
      (runtime/configure! {:cache-dir directory})
      (let [report (with-redefs [runtime/invoke! forbidden runtime/invoke-with-result! forbidden
                                 runtime/run-test! forbidden runtime/precompile-test! forbidden]
                     (precompile/precompile! {:analyze [fixture] :parallelism 1
                                              :report-file (str directory "/report.edn")}))
            analysis (first (:analysis report))]
        (is (empty? (get-in report [:compiler-work :by-phase :native-test :compiler])))
        (is (every? #(and (empty? (:test-checks %)) (empty? (:test-owners %))) (:namespace-images report)))
        (is (some #(= :test (:declaration-kind %)) (:operations analysis)))
        (is (zero? (get-in report [:coverage :runtime-candidates :not-fully-prepared])))
        (with-redefs [work/run-command! (fn [& _] (throw (ex-info "Prepared ordinary call compiled" {})))]
          (is (= 42 (a/value ((resolve (symbol (str fixture) "increment")) 41))))
          ;; Evaluate the actual authored test body as Clojure/JVM forms, not
          ;; through the native test runner. The existing call planners must hit.
          (binding [*ns* (the-ns fixture)]
            (let [declaration (:aguafria/declaration (meta (resolve (symbol (str fixture) "native-success"))))
                  body (drop 2 (:clojure-form declaration))]
              (is (seq body))
              (is (= [{:ok nil}] (mapv #(a/value (eval %)) body)))))))
      ;; Calling a/deftest itself still uses the existing native compiler/runner.
      (let [collector (work/collector)
            result (binding [work/*collector* collector]
                     ((resolve (symbol (str fixture) "native-success"))))]
        (is (zero? (:exit result)))
        (is (pos? (:compiler-invocations (work/report collector)))))
      (finally (runtime/configure! configuration)))))
