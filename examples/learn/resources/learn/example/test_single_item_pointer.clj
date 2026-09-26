(ns learn.example.test-single-item-pointer
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest address-of-syntax
  ;; Get the address of a variable:
  (let [x (k/i32 1234)
        x-ptr (k/& x)]
    ;; Dereference a pointer:
    (try (testing/expectEqual 1234 @x-ptr))
    ;; When you get the address of a const variable, you get a const single-item pointer.
    (try (testing/expectEqual (az/type [:*const :i32])
                              (k/TypeOf x-ptr))))
  ;; If you want to mutate the value, you'd need an address of a mutable variable:
  (let [y (k/var 5678 :i32)
        y-ptr (k/& y)]
    (try (testing/expectEqual (az/type [:* :i32]) (k/TypeOf y-ptr)))
    (k/+= @y-ptr 1)
    (try (testing/expectEqual 5679 @y-ptr))))

(az/deftest pointer-array-access
  ;; Taking an address of an individual element gives a
  ;; single-item pointer. This kind of pointer
  ;; does not support pointer arithmetic.
  (let [array (k/var (az/array [1 2 3 4 5 6 7 8 9 10] :u8))
        ptr (k/& (az/get array 2))]
    (try (testing/expectEqual (az/type [:* :u8]) (k/TypeOf ptr)))
    (try (testing/expectEqual 3 (az/get array 2)))
    (k/+= @ptr 1)
    (try (testing/expectEqual 4 (az/get array 2)))))

(az/deftest slice-syntax
  ;; Get a pointer to a variable:
  (let [x (k/var 1234 :i32)
        x-ptr (k/& x)
        ;; Convert to array pointer using slice syntax:
        x-array-ptr (az/slice x-ptr 0 1)]
    (try (testing/expectEqual (az/type [:* [:array 1 :i32]])
                              (k/TypeOf x-array-ptr)))
    ;; Coerce to many-item pointer:
    (let [x-many-ptr (k/as x-array-ptr [:many :i32])]
      (try (testing/expectEqual 1234 (az/get x-many-ptr 0))))))

(comment
  (address-of-syntax)
  (pointer-array-access)
  (slice-syntax))
