(ns learn.example.test-if-optionals
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest if-optional-test
  ;; If expressions test for null.
  (let [present (k/as 0 [:optional :u32])
        absent (k/as nil [:optional :u32])]
    (az/if-capture-stmt {:payload [value]} present
                        (try (testing/expectEqual 0 value))
                        (k/unreachable))

    (az/if-capture-stmt {:payload [_]} absent
                        (k/unreachable)
                        (try (testing/expect true)))

    ;; The else is not required.
    (az/if-capture-stmt {:payload [value]} present
                        (try (testing/expectEqual 0 value)))

    ;; To test against null only, use the binary equality operator.
    (when (k/== absent nil)
      (try (testing/expect true))))

  ;; Access the value by reference using a pointer capture.
  (let [optional-value (k/var 3 [:optional :u32])]
    (az/if-capture-stmt {:payload [(az/pointer-capture value)]} optional-value
                        (k/= @value 2))

    (az/if-capture-stmt {:payload [value]} optional-value
                        (try (testing/expectEqual 2 value))
                        (k/unreachable))))

(az/deftest if-error-union-optional-test
  ;; If expressions test for errors before unwrapping optionals.
  ;; The optional-value capture has type ?u32.
  (let [present (k/as 0 [:error-union :anyerror [:optional :u32]])
        absent (k/as nil [:error-union :anyerror [:optional :u32]])
        failure (k/as (az/error-value :BadValue) [:error-union :anyerror [:optional :u32]])]
    (az/if-capture-stmt {:payload [optional-value] :error [error]} present
                        (try (testing/expectEqual 0 (az/unwrap optional-value)))
                        (az/block
                          (k/= :_ error)
                          (k/unreachable)))

    (az/if-capture-stmt {:payload [optional-value] :error [_]} absent
                        (try (testing/expectEqual nil optional-value))
                        (k/unreachable))

    (az/if-capture-stmt {:payload [optional-value] :error [error]} failure
                        (az/block
                          (k/= :_ optional-value)
                          (k/unreachable))
                        (try (testing/expectEqual (az/error-value :BadValue) error))))

  ;; Access the value by reference by using a pointer capture each time.
  (let [result (k/var 3 [:error-union :anyerror [:optional :u32]])]
    (az/if-capture-stmt {:payload [(az/pointer-capture optional-value)] :error [_]}
                        result
                        (az/if-capture-stmt {:payload [(az/pointer-capture value)]} @optional-value
                                            (k/= @value 9))
                        (k/unreachable))

    (az/if-capture-stmt {:payload [optional-value] :error [_]} result
                        (try (testing/expectEqual 9 (az/unwrap optional-value)))
                        (k/unreachable))))

(comment
  (if-optional-test)
  (if-error-union-optional-test))
