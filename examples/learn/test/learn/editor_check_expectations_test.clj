(ns learn.editor-check-expectations-test
  "Matcher regressions only; actual native evidence lives in the strict worker receipts."
  (:require [aguafria.zig.check-worker :as worker]
            [clojure.test :refer [deftest is]]
            [learn.editor-check-expectations :as expectations]))

(deftest reviewed-batch-two-negatives-require-their-observed-stage-type-and-data
  (doseq [[namespace message]
          [['learn.example.test-basic-slices
            "Native Zig panic: index out of bounds: index 10, len 4"]
           ['learn.example.test-sentinel-mismatch
            "Native Zig panic: sentinel mismatch: expected 0, found 1"]
           ['learn.example.bad-default-value
            "Native Zig panic: reached unreachable code\nAguafria source locations:\n  --> /root/bad_default_value.clj:17:15"]
           ['learn.example.test-wrong-union-access
            "Native Zig panic: access of union field 'float' while field 'int' is active"]
           ['learn.example.test-blocks
            "Unresolved Zig reference `x`. Define it before use."]]]
    (let [expect (expectations/expectation namespace)
          observation {:exception (-> (:exception expect)
                                      (dissoc :message-pattern)
                                      (assoc :message message))}]
      (is (#'worker/matches? expect observation))
      (is (not (#'worker/matches? expect (assoc-in observation [:exception :stage] :other))))
      (is (not (#'worker/matches? expect (assoc-in observation [:exception :type] 'java.lang.Exception))))
      (is (not (#'worker/matches? expect (assoc-in observation [:exception :message]
                                                  "Strict prepared consumer attempted compilation"))))
      (doseq [key (keys (get-in expect [:exception :data]))]
        (is (not (#'worker/matches? expect (update-in observation [:exception :data] dissoc key))))
        (is (not (#'worker/matches? expect (assoc-in observation [:exception :data key] ::wrong))))))))
