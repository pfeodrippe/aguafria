(ns learn.examples.idiomatic-basics.if-optionals
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest if-optional-test
  ;; If expressions test for null.
  (let [^{:zig/type [:optional :u32]} present 0
        ^{:zig/type [:optional :u32]} absent nil]
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
    (when (== absent nil)
      (try (testing/expect true))))

  ;; Access the value by reference using a pointer capture.
  (let [^{:var [:optional :u32]} optional-value 3]
    (az/if-capture-stmt {:payload [(az/pointer-capture value)]} optional-value
      (set! @value 2))

    (az/if-capture-stmt {:payload [value]} optional-value
      (try (testing/expectEqual 2 value))
      (ak/unreachable))))

(az/deftest if-error-union-optional-test
  ;; If expressions test for errors before unwrapping optionals.
  ;; The optional-value capture has type ?u32.
  (let [^{:zig/type [:error-union :anyerror [:optional :u32]]} present 0
        ^{:zig/type [:error-union :anyerror [:optional :u32]]} absent nil
        ^{:zig/type [:error-union :anyerror [:optional :u32]]}
        failure (az/error-value :BadValue)]
    (az/if-capture-stmt {:payload [optional-value] :error [error]} present
      (try (testing/expectEqual 0 (az/unwrap optional-value)))
      (az/block
        (set! _ error)
        (ak/unreachable)))

    (az/if-capture-stmt {:payload [optional-value] :error [_]} absent
      (try (testing/expectEqual nil optional-value))
      (ak/unreachable))

    (az/if-capture-stmt {:payload [optional-value] :error [error]} failure
      (az/block
        (set! _ optional-value)
        (ak/unreachable))
      (try (testing/expectEqual (az/error-value :BadValue) error))))

  ;; Access the value by reference by using a pointer capture each time.
  (let [^{:var [:error-union :anyerror [:optional :u32]]} result 3]
    (az/if-capture-stmt {:payload [(az/pointer-capture optional-value)] :error [_]}
      result
      (az/if-capture-stmt {:payload [(az/pointer-capture value)]} @optional-value
        (set! @value 9))
      (ak/unreachable))

    (az/if-capture-stmt {:payload [optional-value] :error [_]} result
      (try (testing/expectEqual 9 (az/unwrap optional-value)))
      (ak/unreachable))))
