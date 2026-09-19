(ns learn.example.test-single-item-pointer
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest address-of-test
  (let [^{:zig/type :i32} fixed-value 1234
        fixed-pointer (& fixed-value)]
    (try (testing/expectEqual 1234 @fixed-pointer))
    (try (testing/expectEqual (az/type [:*const :i32])
                              (ak/TypeOf fixed-pointer))))
  (let [^{:var :i32} value 5678
        pointer (& value)]
    (try (testing/expectEqual (az/type [:* :i32]) (ak/TypeOf pointer)))
    (ak/+= @pointer 1)
    (try (testing/expectEqual 5679 @pointer))))

(az/deftest pointer-array-access-test
  (let [^:var numbers (az/array-init [:array _ :u8] [1 2 3 4 5 6 7 8 9 10])
        pointer (& (az/index numbers 2))]
    ;; Addressing one element gives a single-item pointer, not a many-item pointer.
    (try (testing/expectEqual (az/type [:* :u8]) (ak/TypeOf pointer)))
    (try (testing/expectEqual 3 (az/index numbers 2)))
    (ak/+= @pointer 1)
    (try (testing/expectEqual 4 (az/index numbers 2)))))

(az/deftest single-item-slice-test
  (let [^{:var :i32} value 1234
        pointer (& value)
        array-pointer (az/slice pointer 0 1)]
    (try (testing/expectEqual (az/type [:* [:array 1 :i32]])
                              (ak/TypeOf array-pointer)))
    (let [^{:zig/type [:many :i32]} many-pointer array-pointer]
      (try (testing/expectEqual 1234 (az/index many-pointer 0))))))
