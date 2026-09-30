(ns learn.jvm-audit-report-test
  (:require [clojure.test :refer [deftest is]]
            [learn.jvm-audit-report :as report]))

(deftest returned-errors-are-not-reported-as-passing-assertions
  (let [result (report/observed-report
                {:examples [{:cases [{:status :passed :printed-value "{:ok nil}"}
                                     {:status :passed :printed-value "{:error {:name \"TestExpectedEqual\"}}"}
                                     {:status :passed :printed-value "#aguafria.zig.value.ZigValue[{:error {:name \"Oops\"}}]"}
                                     {:status :failed :message "real exception"}]}]})
        cases (get-in result [:examples 0 :cases])]
    (is (= {:passed 1 :returned-error-value 2 :failed 1} (:summary result)))
    (is (= :passed (:evaluation-status (second cases))))
    (is (= "real exception" (:message (last cases))))))
