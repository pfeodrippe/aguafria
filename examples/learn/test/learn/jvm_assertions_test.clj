(ns learn.jvm-assertions-test
  (:require [aguafria.std.testing :as native-testing]
            [aguafria.zig :as a]
            [aguafria.zig.jvm :as jvm]
            [clojure.test :refer [deftest is]]
            [learn.jvm-assertions :as assertions]))

(deftest failed-expectation-cannot-be-hidden-by-a-later-result
  (let [calls (atom [])
        reference {:symbol 'aguafria.std.testing/expectEqual}
        failure {:error {:name "TestExpectedEqual"}}]
    (with-redefs [jvm/invoke-reference! (fn [function arguments]
                                          (swap! calls conj [function arguments])
                                          failure)]
      (let [exception (try
                        (assertions/call-with-checks
                         #(do (jvm/invoke-reference! reference [1 2]) :otherwise-passed))
                        (catch clojure.lang.ExceptionInfo error error))]
        (is (= "Native testing expectation failed during JVM replay" (ex-message exception)))
        (is (= {:function 'aguafria.std.testing/expectEqual :native-result failure}
               (ex-data exception)))
        (is (= [[reference [1 2]]] @calls))))))

(deftest successful-expectations-and-application-errors-keep-their-results
  (doseq [[function result]
          [['aguafria.std.testing/expectEqual {:ok nil}]
           ['aguafria.std.testing/expectError {:ok nil}]
           ['aguafria.std.testing/expect true]
           ['aguafria.std.testing/expectEqual nil]
           ['fixture/expectEqual {:error {:name "ApplicationError"}}]
           ['aguafria.std.testing/allocator {:error {:name "OutOfMemory"}}]]]
    (let [reference {:symbol function} calls (atom 0)]
      (with-redefs [jvm/invoke-reference! (fn [_ _] (swap! calls inc) result)]
        (is (identical? result
                        (assertions/call-with-checks
                         #(jvm/invoke-reference! reference []))))
        (is (= 1 @calls))))))

(deftest assertion-observation-restores-the-original-native-entry-point
  (let [original jvm/invoke-reference!]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"body failed"
                          (assertions/call-with-checks
                           #(throw (ex-info "body failed" {})))))
    (is (identical? original jvm/invoke-reference!))))

(deftest real-native-expectations-are-observed-before-the-body-discards-them
  (is (= :passed
         (assertions/call-with-checks
          #(do (native-testing/expectEqual 1 1) :passed))))
  (let [original jvm/invoke-reference!
        exception (try
                    (assertions/call-with-checks
                     #(do (native-testing/expectEqual 1 2) :otherwise-passed))
                    (catch clojure.lang.ExceptionInfo error error))]
    (is (= "Native testing expectation failed during JVM replay" (ex-message exception)))
    (is (= 'aguafria.std.testing/expectEqual (:function (ex-data exception))))
    (is (= #{:error} (set (keys (:native-result (ex-data exception))))))
    (is (identical? original jvm/invoke-reference!))
    (is (= {:ok nil} (a/value (native-testing/expectEqual 2 2))))))
