(ns learn.example.test-variable-func-alignment
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Pointer :as pointer-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defvar foo :u8 {:align 4} 100)

(a/deftest global-variable-alignment
  (k/try (testing/expectEqual
          4 (-> (typeInfo (TypeOf (k/& foo)))
                type-info/-pointer
                pointer-info/-attrs
                :align)))
  (k/try (testing/expectEqual (a/type [:* {:align 4} :u8])
                              (TypeOf (k/& foo))))
  (let [as-pointer-to-array (as (k/& foo) [:* {:align 4} [:array 1 :u8]])
        as-slice (as as-pointer-to-array [:* {:align 4, :size :slice} :u8])
        as-unaligned-slice (as as-slice [:slice :u8])]
    (k/try (testing/expectEqual 100 (a/get as-unaligned-slice 0)))))

(a/defn derp :i32
  {:align (* (sizeOf :usize) 2)} []
  1234)

(a/defn noop1 :void {:align 1} [])
(a/defn noop4 :void {:align 4} [])

(a/deftest function-alignment
  (k/try (testing/expectEqual 1234 (derp)))
  (k/try (testing/expectEqual (a/type [:fn {} [] :i32]) (TypeOf derp)))
  (k/try (testing/expectEqual
          (a/type [:* {:const? true :align (* (sizeOf :usize) 2)}
                   [:fn {} [] :i32]])
          (TypeOf (k/& derp))))
  (noop1)
  (k/try (testing/expectEqual (a/type [:fn {} [] :void]) (TypeOf noop1)))
  (k/try (testing/expectEqual
          (a/type [:* {:const? true :align 1} [:fn {} [] :void]])
          (TypeOf (k/& noop1))))
  (noop4)
  (k/try (testing/expectEqual (a/type [:fn {} [] :void]) (TypeOf noop4)))
  (k/try (testing/expectEqual
          (a/type [:* {:const? true :align 4} [:fn {} [] :void]])
          (TypeOf (k/& noop4)))))

(comment
  (global-variable-alignment)
  (function-alignment))
