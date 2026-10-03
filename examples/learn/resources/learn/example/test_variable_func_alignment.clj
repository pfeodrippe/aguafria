(ns learn.example.test-variable-func-alignment
  (:require [aguafria.keyword :as k]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Pointer :as pointer-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defvar foo :u8 {:align 4} 100)

(a/deftest global-variable-alignment
  (try (testing/expectEqual
        4 (-> (k/typeInfo (k/TypeOf (k/& foo)))
              type-info/-pointer
              pointer-info/-attrs
              :align)))
  (try (testing/expectEqual (a/type [:* {:align 4} :u8])
                            (k/TypeOf (k/& foo))))
  (let [as-pointer-to-array (k/as (k/& foo) [:* {:align 4} [:array 1 :u8]])
        as-slice (k/as as-pointer-to-array [:* {:align 4, :size :slice} :u8])
        as-unaligned-slice (k/as as-slice [:slice :u8])]
    (try (testing/expectEqual 100 (a/get as-unaligned-slice 0)))))

(a/defn derp :i32
  {:align (k/* (k/sizeOf :usize) 2)} []
  1234)

(a/defn noop1 :void {:align 1} [])
(a/defn noop4 :void {:align 4} [])

(a/deftest function-alignment
  (try (testing/expectEqual 1234 (derp)))
  (try (testing/expectEqual (a/type [:fn {} [] :i32]) (k/TypeOf derp)))
  (try (testing/expectEqual
        (a/type [:* {:const? true :align (k/* (k/sizeOf :usize) 2)}
                 [:fn {} [] :i32]])
        (k/TypeOf (k/& derp))))
  (noop1)
  (try (testing/expectEqual (a/type [:fn {} [] :void]) (k/TypeOf noop1)))
  (try (testing/expectEqual
        (a/type [:* {:const? true :align 1} [:fn {} [] :void]])
        (k/TypeOf (k/& noop1))))
  (noop4)
  (try (testing/expectEqual (a/type [:fn {} [] :void]) (k/TypeOf noop4)))
  (try (testing/expectEqual
        (a/type [:* {:const? true :align 4} [:fn {} [] :void]])
        (k/TypeOf (k/& noop4)))))

(comment
  (global-variable-alignment)
  (function-alignment))
