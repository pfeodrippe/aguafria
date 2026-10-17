(ns learn.example.test-if-optionals
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest if-optional
  ;; If expressions test for null.
  (let [a (as 0 [:optional :u32])
        b (as nil [:optional :u32])]
    (a/if-capture-stmt {:payload [value]} a
                       (k/try (testing/expectEqual 0 value))
                       (unreachable))

    (a/if-capture-stmt {:payload [_]} b
                       (unreachable)
                       (k/try (testing/expect true)))

    ;; The else is not required.
    (a/if-capture-stmt {:payload [value]} a
                       (k/try (testing/expectEqual 0 value)))

    ;; To test against null only, use the binary equality operator.
    (when (== b nil)
      (k/try (testing/expect true))))

  ;; Access the value by reference using a pointer capture.
  (let [c (k/var 3 [:optional :u32])]
    (a/if-capture-stmt {:payload [(a/pointer-capture value)]} c
                       (= @value 2))

    (a/if-capture-stmt {:payload [value]} c
                       (k/try (testing/expectEqual 2 value))
                       (unreachable))))

(a/deftest if-error-union-with-optional
  ;; If expressions test for errors before unwrapping optionals.
  ;; The |optional_value| capture's type is ?u32.
  (let [a (as 0 [:error-union :anyerror [:optional :u32]])
        b (as nil [:error-union :anyerror [:optional :u32]])
        c (as (a/error-value :BadValue) [:error-union :anyerror [:optional :u32]])]
    (a/if-capture-stmt {:payload [optional-value] :error [err]} a
                       (k/try (testing/expectEqual 0 (a/unwrap optional-value)))
                       (a/block
                        (= :_ err)
                        (unreachable)))

    (a/if-capture-stmt {:payload [optional-value] :error [_]} b
                       (k/try (testing/expectEqual nil optional-value))
                       (unreachable))

    (a/if-capture-stmt {:payload [optional-value] :error [err]} c
                       (a/block
                        (= :_ optional-value)
                        (unreachable))
                       (k/try (testing/expectEqual (a/error-value :BadValue) err))))

  ;; Access the value by reference by using a pointer capture each time.
  (let [d (k/var 3 [:error-union :anyerror [:optional :u32]])]
    (a/if-capture-stmt {:payload [(a/pointer-capture optional-value)] :error [_]}
                       d
                       (a/if-capture-stmt {:payload [(a/pointer-capture value)]} @optional-value
                                          (= @value 9))
                       (unreachable))

    (a/if-capture-stmt {:payload [optional-value] :error [_]} d
                       (k/try (testing/expectEqual 9 (a/unwrap optional-value)))
                       (unreachable))))

(comment
  (if-optional)
  (if-error-union-with-optional))
