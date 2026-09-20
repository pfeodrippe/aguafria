(ns learn.example.test-variable-func-alignment
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defvar aligned-byte {:zig/qualifiers "align(4)"} :u8 100)

(az/deftest global-variable-alignment-test
  (let [information (az/field (ak/typeInfo (ak/TypeOf (& aligned-byte))) :pointer)]
    (try (testing/expectEqual 4 (az/field information :alignment))))
  (try (testing/expectEqual (az/type [:pointer {:size :one :align 4} :u8])
                            (ak/TypeOf (& aligned-byte))))
  (let [array-pointer (ak/as (& aligned-byte) [:pointer {:align 4, :size :one} [:array 1 :u8]])
        aligned-slice (ak/as array-pointer [:pointer {:align 4, :size :slice} :u8])
        ordinary-slice (ak/as aligned-slice [:slice :u8])]
    ;; Coercion may weaken an alignment guarantee without changing the data.
    (try (testing/expectEqual 100 (az/index ordinary-slice 0)))))

(az/defn derp :i32
  {:zig/qualifiers "align(@sizeOf(usize) * 2)"} []
  1234)

(az/defn noop1 :void {:zig/qualifiers "align(1)"} [])
(az/defn noop4 :void {:zig/qualifiers "align(4)"} [])

(az/deftest function-alignment-test
  (try (testing/expectEqual 1234 (derp)))
  (try (testing/expectEqual (az/type [:fn {} [] :i32]) (ak/TypeOf derp)))
  (try (testing/expectEqual
        (az/type [:pointer {:size :one :const? true :align (* (ak/sizeOf (az/type :usize)) 2)}
                  [:fn {} [] :i32]])
        (ak/TypeOf (& derp))))
  (noop1)
  (try (testing/expectEqual (az/type [:fn {} [] :void]) (ak/TypeOf noop1)))
  (try (testing/expectEqual
        (az/type [:pointer {:size :one :const? true :align 1} [:fn {} [] :void]])
        (ak/TypeOf (& noop1))))
  (noop4)
  (try (testing/expectEqual (az/type [:fn {} [] :void]) (ak/TypeOf noop4)))
  (try (testing/expectEqual
        (az/type [:pointer {:size :one :const? true :align 4} [:fn {} [] :void]])
        (ak/TypeOf (& noop4)))))

(comment
  (global-variable-alignment-test)
  (function-alignment-test))
