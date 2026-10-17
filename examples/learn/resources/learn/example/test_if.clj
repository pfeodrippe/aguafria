(ns learn.example.test-if
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

;; If expressions have three uses, corresponding to the three types:
;; * bool
;; * ?T
;; * anyerror!T
(a/deftest if-expression
  ;; If expressions are used instead of a ternary expression.
  (let [a (u32 5)
        b (u32 4)
        result (if (!= a b)
                 47
                 3089)]
    (k/try (testing/expectEqual result 47))))

(a/deftest if-boolean
  ;; If expressions test boolean conditions.
  (let [a (u32 5)
        b (u32 4)]
    (if (!= a b)
      (k/try (testing/expect true))
      (if (== a 9)
        (unreachable)
        (unreachable)))))

(a/deftest if-error-union
  ;; If expressions test for errors.
  ;; Note the |err| capture on the else.
  (let [a (as 0 [:error-union :anyerror :u32])
        b (as (a/error-value :BadValue) [:error-union :anyerror :u32])]
    (a/if-capture-stmt {:payload [value] :error [err]} a
                       (k/try (testing/expectEqual value 0))
                       (a/block
                        (= :_ err)
                        (unreachable)))

    (a/if-capture-stmt {:payload [value] :error [err]} b
                       (a/block
                        (= :_ value)
                        (unreachable))
                       (k/try (testing/expectEqual err (a/error-value :BadValue))))

    ;; The else and |err| capture is strictly required.
    (a/if-capture-stmt {:payload [value] :error [_]} a
                       (k/try (testing/expectEqual value 0))
                       (a/block))

    ;; To check only the error value, use an empty block expression.
    (a/if-capture-stmt {:payload [_] :error [err]} b
                       (a/block)
                       (k/try (testing/expectEqual err (a/error-value :BadValue)))))

  ;; Access the value by reference using a pointer capture.
  (let [c (k/var 3 [:error-union :anyerror :u32])]
    (a/if-capture-stmt {:payload [(a/pointer-capture value)] :error [_]} c
                       (= @value 9)
                       (unreachable))

    (a/if-capture-stmt {:payload [value] :error [_]} c
                       (k/try (testing/expectEqual value 9))
                       (unreachable))))

(comment
  (if-expression)
  (if-boolean)
  (if-error-union))
