(ns learn.example.test-single-item-pointer
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest address-of-test
  (let [fixed-value (k/i32 1234)
        fixed-pointer (k/& fixed-value)]
    (try (testing/expectEqual 1234 @fixed-pointer))
    (try (testing/expectEqual (az/type [:*const :i32])
                              (k/TypeOf fixed-pointer))))
  (let [value (k/var 5678 :i32)
        pointer (k/& value)]
    (try (testing/expectEqual (az/type [:* :i32]) (k/TypeOf pointer)))
    (k/+= @pointer 1)
    (try (testing/expectEqual 5679 @pointer))))

(az/deftest pointer-array-access-test
  (let [numbers (k/var (az/array-init [1 2 3 4 5 6 7 8 9 10] [:array :_ :u8]))
        pointer (k/& (az/index numbers 2))]
    ;; Addressing one element gives a single-item pointer, not a many-item pointer.
    (try (testing/expectEqual (az/type [:* :u8]) (k/TypeOf pointer)))
    (try (testing/expectEqual 3 (az/index numbers 2)))
    (k/+= @pointer 1)
    (try (testing/expectEqual 4 (az/index numbers 2)))))

(az/deftest single-item-slice-test
  (let [value (k/var 1234 :i32)
        pointer (k/& value)
        array-pointer (az/slice pointer 0 1)]
    (try (testing/expectEqual (az/type [:* [:array 1 :i32]])
                              (k/TypeOf array-pointer)))
    (let [many-pointer (k/as array-pointer [:many :i32])]
      (try (testing/expectEqual 1234 (az/index many-pointer 0))))))

(comment
  (address-of-test)
  (pointer-array-access-test)
  (single-item-slice-test))
