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
  (let [^{:zig/type [:pointer {:size :one :align 4} [:array 1 :u8]]}
        array-pointer (& aligned-byte)
        ^{:zig/type [:pointer {:size :slice :align 4} :u8]} aligned-slice array-pointer
        ^{:zig/type [:slice :u8]} ordinary-slice aligned-slice]
    ;; Coercion may weaken an alignment guarantee without changing the data.
    (try (testing/expectEqual 100 (az/index ordinary-slice 0)))))

(az/defn aligned-answer :i32
  {:zig/qualifiers "align(@sizeOf(usize) * 2)"} []
  1234)

(az/defn noop-one :void {:zig/qualifiers "align(1)"} [])
(az/defn noop-four :void {:zig/qualifiers "align(4)"} [])

(az/deftest function-alignment-test
  (try (testing/expectEqual 1234 (aligned-answer)))
  (try (testing/expectEqual (az/type [:fn {} [] :i32]) (ak/TypeOf aligned-answer)))
  (try (testing/expectEqual
         (az/type [:pointer {:size :one :const? true :align (* (ak/sizeOf (az/type :usize)) 2)}
                   [:fn {} [] :i32]])
         (ak/TypeOf (& aligned-answer))))
  (noop-one)
  (try (testing/expectEqual (az/type [:fn {} [] :void]) (ak/TypeOf noop-one)))
  (try (testing/expectEqual
         (az/type [:pointer {:size :one :const? true :align 1} [:fn {} [] :void]])
         (ak/TypeOf (& noop-one))))
  (noop-four)
  (try (testing/expectEqual (az/type [:fn {} [] :void]) (ak/TypeOf noop-four)))
  (try (testing/expectEqual
         (az/type [:pointer {:size :one :const? true :align 4} [:fn {} [] :void]])
         (ak/TypeOf (& noop-four)))))
