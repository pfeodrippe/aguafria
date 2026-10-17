(ns learn.example.test-single-item-pointer
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest address-of-syntax
  ;; Get the address of a variable:
  (let [x (i32 1234)
        x-ptr (k/& x)]
    ;; Dereference a pointer:
    (k/try (testing/expectEqual 1234 @x-ptr))
    ;; When you get the address of a const variable, you get a const single-item pointer.
    (k/try (testing/expectEqual (a/type [:*const :i32])
                                (TypeOf x-ptr))))
  ;; If you want to mutate the value, you'd need an address of a mutable variable:
  (let [y (k/var 5678 :i32)
        y-ptr (k/& y)]
    (k/try (testing/expectEqual (a/type [:* :i32]) (TypeOf y-ptr)))
    (+= @y-ptr 1)
    (k/try (testing/expectEqual 5679 @y-ptr))))

(a/deftest pointer-array-access
  ;; Taking an address of an individual element gives a
  ;; single-item pointer. This kind of pointer
  ;; does not support pointer arithmetic.
  (let [array (k/var (a/array [1 2 3 4 5 6 7 8 9 10] :u8))
        ptr (k/& (a/get array 2))]
    (k/try (testing/expectEqual (a/type [:* :u8]) (TypeOf ptr)))
    (k/try (testing/expectEqual 3 (a/get array 2)))
    (+= @ptr 1)
    (k/try (testing/expectEqual 4 (a/get array 2)))))

(a/deftest slice-syntax
  ;; Get a pointer to a variable:
  (let [x (k/var 1234 :i32)
        x-ptr (k/& x)
        ;; Convert to array pointer using slice syntax:
        x-array-ptr (a/slice x-ptr 0 1)]
    (k/try (testing/expectEqual (a/type [:* [:array 1 :i32]])
                                (TypeOf x-array-ptr)))
    ;; Coerce to many-item pointer:
    (let [x-many-ptr (as x-array-ptr [:many :i32])]
      (k/try (testing/expectEqual 1234 (a/get x-many-ptr 0))))))

(comment
  (address-of-syntax)
  (pointer-array-access)
  (slice-syntax))
