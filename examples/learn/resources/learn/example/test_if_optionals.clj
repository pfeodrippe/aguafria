(ns learn.example.test-if-optionals
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest if-optional
  ;; If expressions test for null.
  (let [a (k/as 0 [:optional :u32])
        b (k/as nil [:optional :u32])]
    (az/if-capture-stmt {:payload [value]} a
                        (try (testing/expectEqual 0 value))
                        (k/unreachable))

    (az/if-capture-stmt {:payload [_]} b
                        (k/unreachable)
                        (try (testing/expect true)))

    ;; The else is not required.
    (az/if-capture-stmt {:payload [value]} a
                        (try (testing/expectEqual 0 value)))

    ;; To test against null only, use the binary equality operator.
    (when (k/== b nil)
      (try (testing/expect true))))

  ;; Access the value by reference using a pointer capture.
  (let [c (k/var 3 [:optional :u32])]
    (az/if-capture-stmt {:payload [(az/pointer-capture value)]} c
                        (k/= @value 2))

    (az/if-capture-stmt {:payload [value]} c
                        (try (testing/expectEqual 2 value))
                        (k/unreachable))))

(az/deftest if-error-union-with-optional
  ;; If expressions test for errors before unwrapping optionals.
  ;; The |optional_value| capture's type is ?u32.
  (let [a (k/as 0 [:error-union :anyerror [:optional :u32]])
        b (k/as nil [:error-union :anyerror [:optional :u32]])
        c (k/as (az/error-value :BadValue) [:error-union :anyerror [:optional :u32]])]
    (az/if-capture-stmt {:payload [optional-value] :error [err]} a
                        (try (testing/expectEqual 0 (az/unwrap optional-value)))
                        (az/block
                         (k/= :_ err)
                         (k/unreachable)))

    (az/if-capture-stmt {:payload [optional-value] :error [_]} b
                        (try (testing/expectEqual nil optional-value))
                        (k/unreachable))

    (az/if-capture-stmt {:payload [optional-value] :error [err]} c
                        (az/block
                         (k/= :_ optional-value)
                         (k/unreachable))
                        (try (testing/expectEqual (az/error-value :BadValue) err))))

  ;; Access the value by reference by using a pointer capture each time.
  (let [d (k/var 3 [:error-union :anyerror [:optional :u32]])]
    (az/if-capture-stmt {:payload [(az/pointer-capture optional-value)] :error [_]}
                        d
                        (az/if-capture-stmt {:payload [(az/pointer-capture value)]} @optional-value
                                            (k/= @value 9))
                        (k/unreachable))

    (az/if-capture-stmt {:payload [optional-value] :error [_]} d
                        (try (testing/expectEqual 9 (az/unwrap optional-value)))
                        (k/unreachable))))

(comment
  (if-optional)
  (if-error-union-with-optional))
