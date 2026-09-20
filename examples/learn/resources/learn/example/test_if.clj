(ns learn.example.test-if
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; If expressions have three uses, corresponding to bool, ?T and anyerror!T.
(az/deftest if-expression-test
  ;; If expressions are used instead of a ternary expression.
  (let [a (ak/u32 5)
        b (ak/u32 4)
        result (if (ak/!= a b)
                 47
                 3089)]
    (try (testing/expectEqual result 47))))

(az/deftest if-boolean-test
  ;; If expressions test boolean conditions.
  (let [a (ak/u32 5)
        b (ak/u32 4)]
    (if (ak/!= a b)
      (try (testing/expect true))
      (if (== a 9)
        (ak/unreachable)
        (ak/unreachable)))))

(az/deftest if-error-union-test
  ;; If expressions test for errors. Note the error capture on the else.
  (let [success (ak/as 0 [:error-union :anyerror :u32])
        failure (ak/as (az/error-value :BadValue) [:error-union :anyerror :u32])]
    (az/if-capture-stmt {:payload [value] :error [error]} success
                        (try (testing/expectEqual value 0))
                        (az/block
                          (set! _ error)
                          (ak/unreachable)))

    (az/if-capture-stmt {:payload [value] :error [error]} failure
                        (az/block
                          (set! _ value)
                          (ak/unreachable))
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
  (let [^:var result (ak/as 3 [:error-union :anyerror :u32])]
    (az/if-capture-stmt {:payload [(az/pointer-capture value)] :error [_]} result
                        (set! @value 9)
                        (ak/unreachable))

    (az/if-capture-stmt {:payload [value] :error [_]} result
                        (try (testing/expectEqual value 9))
                        (ak/unreachable))))

(comment
  (if-expression-test)
  (if-boolean-test)
  (if-error-union-test))
