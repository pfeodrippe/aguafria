(ns aguafria.zig.editor-detect-context-test
  "Declaration provenance must not turn a successful ordinary adapter into a test build."
  (:require [aguafria.zig.discovery :as discovery]
            [aguafria.zig.jvm :as jvm]
            [aguafria.zig.runtime :as runtime]
            [clojure.test :refer [deftest is]]))

(deftest successful-test-declaration-adapters-retain-ordinary-execution-context
  ;; Unlike the rejection/retry coverage in discovery-test, all adapters here
  ;; compile successfully in ordinary context. Lexical :test is provenance,
  ;; not a demand for builtin.is_test=true. No native compilation in this test.
  (let [contexts (atom [])
        operation {:status :observed :function 'aguafria.keyword/+
                   :signatures [[:u32 :u32]]}
        report (with-redefs [discovery/analyze!
                            (fn [_] {:basis :zig-compiler :namespace 'aguafria.zig.editor-detect-context-test
                                     :operations [(assoc operation :declaration-kind :fn)
                                                  (assoc operation :declaration-kind :test)
                                                  (assoc operation :declaration-kind :test)]})
                            jvm/precompile-call!
                            (fn [_]
                              (swap! contexts conj runtime/*native-test-context?*)
                              {:status :prepared})]
                 (with-redefs-fn {#'discovery/prepare-declared-functions! (constantly [])}
                   #(discovery/prepare! 'aguafria.zig.editor-detect-context-test)))]
    (is (= [false] @contexts)
        "Declaration provenance alone does not split identical ordinary demands")
    (is (= [:runtime :test :test] (mapv :enclosing-context (:operations report))))
    (is (= [:runtime :runtime :runtime]
           (mapv #(get-in % [:handlers 0 :execution-context]) (:operations report))))
    (is (every? #(= :prepared (:status %)) (mapcat :handlers (:operations report))))
    (is (false? runtime/*native-test-context?*))))
