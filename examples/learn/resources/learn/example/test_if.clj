(ns learn.example.test-if
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

;; If expressions have three uses, corresponding to the three types:
;; * bool
;; * ?T
;; * anyerror!T
(az/deftest if-expression
  ;; If expressions are used instead of a ternary expression.
  (let [a (k/u32 5)
        b (k/u32 4)
        result (if (k/!= a b)
                 47
                 3089)]
    (try (testing/expectEqual result 47))))

(az/deftest if-boolean
  ;; If expressions test boolean conditions.
  (let [a (k/u32 5)
        b (k/u32 4)]
    (if (k/!= a b)
      (try (testing/expect true))
      (if (k/== a 9)
        (k/unreachable)
        (k/unreachable)))))

(az/deftest if-error-union
  ;; If expressions test for errors.
  ;; Note the |err| capture on the else.
  (let [a (k/as 0 [:error-union :anyerror :u32])
        b (k/as (az/error-value :BadValue) [:error-union :anyerror :u32])]
    (az/if-capture-stmt {:payload [value] :error [err]} a
                        (try (testing/expectEqual value 0))
                        (az/block
                         (k/= :_ err)
                         (k/unreachable)))

    (az/if-capture-stmt {:payload [value] :error [err]} b
                        (az/block
                         (k/= :_ value)
                         (k/unreachable))
                        (try (testing/expectEqual err (az/error-value :BadValue))))

    ;; The else and |err| capture is strictly required.
    (az/if-capture-stmt {:payload [value] :error [_]} a
                        (try (testing/expectEqual value 0))
                        (az/block))

    ;; To check only the error value, use an empty block expression.
    (az/if-capture-stmt {:payload [_] :error [err]} b
                        (az/block)
                        (try (testing/expectEqual err (az/error-value :BadValue)))))

  ;; Access the value by reference using a pointer capture.
  (let [c (k/var 3 [:error-union :anyerror :u32])]
    (az/if-capture-stmt {:payload [(az/pointer-capture value)] :error [_]} c
                        (k/= @value 9)
                        (k/unreachable))

    (az/if-capture-stmt {:payload [value] :error [_]} c
                        (try (testing/expectEqual value 9))
                        (k/unreachable))))

(comment
  (if-expression)
  (if-boolean)
  (if-error-union))
