(ns learn.values-cache-check-test
  (:require [clojure.test :refer [deftest is testing]]
            [learn.values-cache-check :as check]))

(deftest values-cache-acceptance-fails-closed
  (let [report {:body-forms 5
                :new-libraries []
                :standalone []
                :output-verified? true
                :native-output-matches? true
                :producer-bundle-id "producer"
                :bundles #{"producer"}
                :events {:bundle-loaded 1 :bundle-cache-hit 2}}]
    (is (= report (check/validate-report! report)))
    (doseq [[label invalid]
            [["missing body" (assoc report :body-forms 4)]
             ["incorrect output" (assoc report :output-verified? false)]
             ["native mismatch" (assoc report :native-output-matches? false)]
             ["new library" (assoc report :new-libraries ["unexpected.dylib"])]
             ["standalone cache hit" (assoc report :standalone [{:module "aguafria.jvm.x"}])]
             ["runtime compilation" (assoc-in report [:events :compiled] 1)]
             ["failed compilation" (assoc-in report [:events :compile-failed] 1)]
             ["wrong pack" (assoc report :bundles #{"another"})]
             ["two packs" (assoc report :bundles #{"producer" "another"})]
             ["no pack" (assoc report :bundles #{})]
             ["no producer" (dissoc report :producer-bundle-id)]
             ["already loaded" (assoc-in report [:events :bundle-loaded] 0)]]]
      (testing label
        (is (thrown? clojure.lang.ExceptionInfo (check/validate-report! invalid)))))))
