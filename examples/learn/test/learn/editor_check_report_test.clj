(ns learn.editor-check-report-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [learn.editor-check-report :as report]
            [learn.editor-check-failures :as failures])
  (:import [java.nio.file Files]))

(deftest reports-observed-results-not-hardcoded-coldness-or-failures
  (let [root (str (Files/createTempDirectory "learn-editor-report-" (make-array java.nio.file.attribute.FileAttribute 0)))
        checkpoint (str (io/file root "checkpoint.edn"))
        producer (str (io/file root "producer.edn"))
        output (str (io/file root "report.md"))]
    (spit checkpoint (pr-str {:status :passed :completed [{:status :evaluated}] :remaining []}))
    (spit producer (pr-str {:parallelism 7 :analysis [{:namespace 'example}]}))
    (spit (io/file root "configuration.edn")
          (pr-str {:parallelism 2 :heap-mib 512 :timeout-seconds 15 :strict-load? true
                   :scenarios [{:id 'example :load-files ["/resources/learn/example/example.clj"]
                                :args ['example "example.clj" checkpoint]}]}))
    (spit (io/file root "report.edn")
          (pr-str {:accepted? true :results [{:scenario 'example :accepted? true :status :passed
                                             :worker {:directory root}}]}))
    (report/report! {:check-dir root :precompile-report producer :resources-dir root :output-file output})
    (let [text (slurp output)]
      (is (str/includes? text "CHECKER ACCEPTED"))
      (is (not (str/includes? text "NOT GREEN")))
      (is (str/includes? text "Producer parallelism: 7"))
      (is (str/includes? text ":parallelism 2"))
      (is (not (str/includes? text "directory was deleted")))
      (is (not (re-find #"(?m)^\s*\|" text))))))

(deftest producer-causes-take-priority-over-blocked-test-commands
  (let [row {:namespace 'fixture :message "Strict prepared consumer attempted compilation"
             :compiler-attempts 1 :artifact-kind "native-test-definition"
             :producer-status :rejected :upstream-expectation :success-or-context}]
    (is (= :undefined-token
           (failures/classify (assoc row :producer-diagnostics ["Bare `undefined` is not a Clojure Var"]))))
    (is (= :testing-context
           (failures/classify (assoc row :producer-diagnostics ["not testing"]))))
    (is (= :unresolved-producer
           (first (failures/classify (assoc row :producer-diagnostics ["new unsupported case"])))))))

(deftest test-snapshot-evidence-separates-required-repairs
  (let [row {:namespace 'fixture :message "blocked" :compiler-attempts 1
             :artifact-kind "native-test-definition" :producer-status :analyzed}]
    (is (= :test-object (failures/classify (assoc row :test-snapshot {:kind :complete}))))
    (is (= :test-prefix (failures/classify (assoc row :test-snapshot {:kind :prefix}))))
    (is (= :transitive-test (failures/classify (assoc row :test-snapshot {:kind :complete :transitive? true}))))
    (is (= :test-shape-unknown (failures/classify row)))))

(deftest editor-context-diagnoses-require-checkpoint-evidence
  (let [tls {:namespace 'learn.example.test-thread-local-variables
             :compiler-attempts 0 :message "Native Zig panic: reached unreachable code"}
        context {:namespace 'learn.example.testing-detect-test
                 :compiler-attempts 0 :message "Native Zig error: TestUnexpectedResult"}]
    (is (= :unresolved-consumer (first (failures/classify tls))))
    (is (= :editor-tls-reentry
           (failures/classify
            (assoc tls :editor-observations
                   (mapv #(hash-map :status :evaluated :expression %)
                         ["(debug/assert (== x 1234))" "(+= x 1)" "(debug/assert (== x 1235))"])))))
    (is (= :unresolved-consumer (first (failures/classify context))))
    (is (= :editor-test-context
           (failures/classify
            (assoc context :editor-observations [{:expression "builtin/is_test" :status :evaluated
                                                  :result "#aguafria.zig.value.ZigValue[false]"}]))))))

(deftest authored-sentinel-negative-is-not-a-request-to-add-sentinel-storage
  (let [row {:namespace 'fixture :message "blocked" :compiler-attempts 1
             :producer-status :rejected
             :producer-diagnostics ["expected type '[*:0]const u8', found '*const [14]u8'"]}]
    (is (= :expected-diagnostic
           (first (failures/classify (assoc row :upstream-expectation :compile-failure)))))
    (is (= :sentinel-string
           (failures/classify (assoc row :upstream-expectation :success-or-context))))))

(deftest upstream-runner-diagnostics-are-not-compiler-failures
  (let [file (.toFile (Files/createTempFile "learn-upstream-intent-" ".zig"
                                          (make-array java.nio.file.attribute.FileAttribute 0)))]
    (doseq [[source expected] [["// test_error=1 tests leaked memory.\n" :test-failure]
                               ["// test_error=\n" :test-failure]
                               ["// test=build_fail\n// test_error=cannot assign to constant\n" :compile-failure]]]
      (spit file source)
      (is (= expected (#'report/expected file))))))

(deftest syntax-only-is-not-proof-of-an-intended-negative
  (is (= :unresolved-producer
         (first (failures/classify {:namespace 'fixture :message "blocked" :compiler-attempts 1
                                    :producer-status :rejected :producer-diagnostics ["some syntax error"]
                                    :upstream-expectation :syntax-only})))))
