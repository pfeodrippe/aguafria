(ns learn.example.test-variable-func-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Pointer :as pointer-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defvar aligned-byte :u8 {:zig/qualifiers "align(4)"}  100)

(az/deftest global-variable-alignment-test
  (let [information (type-info/-pointer (k/typeInfo (k/TypeOf (k/& aligned-byte))))]
    (try (testing/expectEqual 4 (pointer-info/-alignment information))))
  (try (testing/expectEqual (az/type [:pointer {:size :one :align 4} :u8])
                            (k/TypeOf (k/& aligned-byte))))
  (let [array-pointer (k/as (k/& aligned-byte) [:pointer {:align 4, :size :one} [:array 1 :u8]])
        aligned-slice (k/as array-pointer [:pointer {:align 4, :size :slice} :u8])
        ordinary-slice (k/as aligned-slice [:slice :u8])]
    ;; Coercion may weaken an alignment guarantee without changing the data.
    (try (testing/expectEqual 100 (az/index ordinary-slice 0)))))

(az/defn derp :i32
  {:zig/qualifiers "align(@sizeOf(usize) * 2)"} []
  1234)

(az/defn noop1 :void {:zig/qualifiers "align(1)"} [])
(az/defn noop4 :void {:zig/qualifiers "align(4)"} [])

(az/deftest function-alignment-test
  (try (testing/expectEqual 1234 (derp)))
  (try (testing/expectEqual (az/type [:fn {} [] :i32]) (k/TypeOf derp)))
  (try (testing/expectEqual
        (az/type [:pointer {:size :one :const? true :align (k/* (k/sizeOf (az/type :usize)) 2)}
                  [:fn {} [] :i32]])
        (k/TypeOf (k/& derp))))
  (noop1)
  (try (testing/expectEqual (az/type [:fn {} [] :void]) (k/TypeOf noop1)))
  (try (testing/expectEqual
        (az/type [:pointer {:size :one :const? true :align 1} [:fn {} [] :void]])
        (k/TypeOf (k/& noop1))))
  (noop4)
  (try (testing/expectEqual (az/type [:fn {} [] :void]) (k/TypeOf noop4)))
  (try (testing/expectEqual
        (az/type [:pointer {:size :one :const? true :align 4} [:fn {} [] :void]])
        (k/TypeOf (k/& noop4)))))

(comment
  (global-variable-alignment-test)
  (function-alignment-test))
