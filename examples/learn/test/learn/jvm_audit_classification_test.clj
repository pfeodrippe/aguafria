(ns learn.jvm-audit-classification-test
  (:require [clojure.test :refer [deftest is]]
            [learn.jvm-audit-classification :as subject]))

(def sample
  {:examples [{:zig "test.zig" :source "test.clj" :evidence-path "cases.edn"
               :cases [{:status :failed} {:status :passed}
                       {:status :requires-arguments}]}]})

(def finding
  {:zig "test.zig" :index 0 :category :jvm-interop-defect
   :family :test-fixture :reason "Reviewed exact diagnostic" :evidence ["cases.edn"]})

(deftest require-exact-reviewed-coverage
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"coverage mismatch"
                        (subject/combine sample [])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"coverage mismatch"
                        (subject/combine sample [{:cases [finding finding]}])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"coverage mismatch"
                        (subject/combine sample [{:cases [finding (assoc finding :index 99)]}]))))

(deftest require-evidence-and-specific-category
  (doseq [bad [(dissoc finding :evidence) (assoc finding :reason "")
               (assoc finding :category :unknown)]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Incomplete reviewed finding"
                          (subject/combine sample [{:cases [bad]}])))))

(deftest preserve-original-outcome-and-context-exclusions
  (let [result (subject/combine sample [{:cases [finding]}])]
    (is (= 3 (count (:cases result))))
    (is (= [:failed :passed :requires-arguments] (mapv :status (:cases result))))
    (is (= {:jvm-interop-defect 1 :passed-probe 1 :unexecuted-function-context 1}
           (:summary result)))
    (is (= {:jvm-interop-defect 1} (:failure-summary result)))))
