(ns aguafria.zig.editor-detect-context-probe
  "Parent-only native probe: run the unchanged lesson through its real editor evaluator.
  This is not strict bundle acceptance and does not change expected-exception policy."
  (:require [aguafria.zig.runtime :as runtime]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn verify!
  "Run once in a fresh, guarded JVM/cache with Learn resource/test paths available.
  Requires an unused :checkpoint path. No native test invocation or test-mode binding."
  [{:keys [checkpoint source]
    :or {source "examples/learn/resources/learn/example/testing_detect_test.clj"}}]
  (assert (and checkpoint (not (.exists (io/file checkpoint))))
          "Supply a new checkpoint path")
  (assert (false? runtime/*native-test-context?*))
  (let [evaluate! (requiring-resolve 'learn.editor-check/evaluate!)
        path (.getAbsolutePath (io/file source))
        namespace 'learn.example.testing-detect-test]
    (with-redefs [runtime/run-test!
                  (fn [& _] (throw (ex-info "Probe forbids native test substitution"
                                            {:probe :native-test-substitution})))]
      ;; Same source-loading primitive as check-worker; do not require the lesson
      ;; and then reload it, or evaluate its helper/test before the editor rows.
      (binding [*ns* *ns*]
        (with-open [reader (io/reader path)]
          (clojure.lang.Compiler/load reader path (.getName (io/file path)))))
      (assert (= :fn (get-in (meta (find-var (symbol (str namespace) "isATest")))
                            [:aguafria/declaration :kind])))
      (assert (= :test (get-in (meta (find-var (symbol (str namespace) "builtin-is-test")))
                              [:aguafria/declaration :kind])))
      (assert (= "@import(\"builtin\").is_test"
                 (get-in (meta (find-var 'aguafria.builtin/is_test))
                         [:aguafria/zig-reference :zig-name])))
      (let [failure (try (evaluate! namespace path checkpoint) nil
                         (catch Throwable error error))
            receipt (edn/read-string (slurp checkpoint))]
        (assert (= {:aguafria/phase :native-error :error-name :TestUnexpectedResult}
                   (select-keys (ex-data failure) [:aguafria/phase :error-name]))
                (str "Expected actual native expectation failure, got " failure))
        (assert (= ["builtin/is_test" "(k/try (testing/expect (isATest)))"]
                   (mapv :expression (:planned receipt))))
        (assert (= 1 (count (:completed receipt))))
        (assert (= "#aguafria.zig.value.ZigValue[false]"
                   (get-in receipt [:completed 0 :result])))
        (assert (= :evaluated (get-in receipt [:completed 0 :status])))
        (assert (= :failed (:status receipt)))
        (assert (= "(k/try (testing/expect (isATest)))"
                   (get-in receipt [:current-row :expression])))
        (assert (= [(:current-row receipt)] (:remaining receipt)))
        (assert (false? runtime/*native-test-context?*))
        (let [report {:probe :ordinary-editor-detect-context
                      :verified-native-error :TestUnexpectedResult
                      :completed 1 :planned 2 :checkpoint checkpoint
                      :strict-bundle-acceptance? false}]
          (prn report)
          report)))))
