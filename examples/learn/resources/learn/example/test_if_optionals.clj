(ns learn.example.test-if-optionals
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest if-optional-test
  ;; If expressions test for null.
  (let [present (ak/as 0 [:optional :u32])
        absent (ak/as nil [:optional :u32])]
    (az/if-capture-stmt {:payload [value]} present
                        (try (testing/expectEqual 0 value))
                        (ak/unreachable))

    (az/if-capture-stmt {:payload [_]} absent
                        (ak/unreachable)
                        (try (testing/expect true)))

    ;; The else is not required.
    (az/if-capture-stmt {:payload [value]} present
                        (try (testing/expectEqual 0 value)))

    ;; To test against null only, use the binary equality operator.
    (when (ak/== absent nil)
      (try (testing/expect true))))

  ;; Access the value by reference using a pointer capture.
  (let [optional-value (ak/var 3 [:optional :u32])]
    (az/if-capture-stmt {:payload [(az/pointer-capture value)]} optional-value
                        (ak/= @value 2))

    (az/if-capture-stmt {:payload [value]} optional-value
                        (try (testing/expectEqual 2 value))
                        (ak/unreachable))))

(az/deftest if-error-union-optional-test
  ;; If expressions test for errors before unwrapping optionals.
  ;; The optional-value capture has type ?u32.
  (let [present (ak/as 0 [:error-union :anyerror [:optional :u32]])
        absent (ak/as nil [:error-union :anyerror [:optional :u32]])
        failure (ak/as (az/error-value :BadValue) [:error-union :anyerror [:optional :u32]])]
    (az/if-capture-stmt {:payload [optional-value] :error [error]} present
                        (try (testing/expectEqual 0 (az/unwrap optional-value)))
                        (az/block
                          (ak/= :_ error)
                          (ak/unreachable)))

    (az/if-capture-stmt {:payload [optional-value] :error [_]} absent
                        (try (testing/expectEqual nil optional-value))
                        (ak/unreachable))

    (az/if-capture-stmt {:payload [optional-value] :error [error]} failure
                        (az/block
                          (ak/= :_ optional-value)
                          (ak/unreachable))
                        (try (testing/expectEqual (az/error-value :BadValue) error))))

  ;; Access the value by reference by using a pointer capture each time.
  (let [result (ak/var 3 [:error-union :anyerror [:optional :u32]])]
    (az/if-capture-stmt {:payload [(az/pointer-capture optional-value)] :error [_]}
                        result
                        (az/if-capture-stmt {:payload [(az/pointer-capture value)]} @optional-value
                                            (ak/= @value 9))
                        (ak/unreachable))

    (az/if-capture-stmt {:payload [optional-value] :error [_]} result
                        (try (testing/expectEqual 9 (az/unwrap optional-value)))
                        (ak/unreachable))))

(comment
  (if-optional-test)
  (if-error-union-optional-test))
