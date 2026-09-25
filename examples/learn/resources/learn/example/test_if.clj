(ns learn.example.test-if
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; If expressions have three uses, corresponding to bool, ?T and anyerror!T.
(az/deftest if-expression-test
  ;; If expressions are used instead of a ternary expression.
  (let [a (k/u32 5)
        b (k/u32 4)
        result (if (k/!= a b)
                 47
                 3089)]
    (try (testing/expectEqual result 47))))

(az/deftest if-boolean-test
  ;; If expressions test boolean conditions.
  (let [a (k/u32 5)
        b (k/u32 4)]
    (if (k/!= a b)
      (try (testing/expect true))
      (if (k/== a 9)
        (k/unreachable)
        (k/unreachable)))))

(az/deftest if-error-union-test
  ;; If expressions test for errors. Note the error capture on the else.
  (let [success (k/as 0 [:error-union :anyerror :u32])
        failure (k/as (az/error-value :BadValue) [:error-union :anyerror :u32])]
    (az/if-capture-stmt {:payload [value] :error [error]} success
                        (try (testing/expectEqual value 0))
                        (az/block
                          (k/= :_ error)
                          (k/unreachable)))

    (az/if-capture-stmt {:payload [value] :error [error]} failure
                        (az/block
                          (k/= :_ value)
                          (k/unreachable))
                        (try (testing/expectEqual error (az/error-value :BadValue))))

    ;; The else branch and error capture are strictly required.
    (az/if-capture-stmt {:payload [value] :error [_]} success
                        (try (testing/expectEqual value 0))
                        (az/block))

    ;; To check only the error value, use an empty block expression.
    (az/if-capture-stmt {:payload [_] :error [error]} failure
                        (az/block)
                        (try (testing/expectEqual error (az/error-value :BadValue)))))

  ;; Access the value by reference using a pointer capture.
  (let [result (k/var 3 [:error-union :anyerror :u32])]
    (az/if-capture-stmt {:payload [(az/pointer-capture value)] :error [_]} result
                        (k/= @value 9)
                        (k/unreachable))

    (az/if-capture-stmt {:payload [value] :error [_]} result
                        (try (testing/expectEqual value 9))
                        (k/unreachable))))

(comment
  (if-expression-test)
  (if-boolean-test)
  (if-error-union-test))
