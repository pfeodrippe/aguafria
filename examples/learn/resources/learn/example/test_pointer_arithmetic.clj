(ns learn.example.test-pointer-arithmetic
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest many-item-arithmetic-test
  (let [numbers (az/array [1 2 3 4] :i32)
        pointer (k/var (k/& numbers) [:many-const :i32])]
    (try (testing/expectEqual 1 (az/get pointer 0)))
    (k/+= pointer 1)
    (try (testing/expectEqual 2 (az/get pointer 0)))
    ;; An open-ended slice of a many-item pointer advances the pointer.
    (try (testing/expectEqual (az/slice pointer 1) (k/+ pointer 1)))
    ;; Pointer subtraction counts elements, not bytes.
    (try (testing/expectEqual 1 (k/- (k/& (az/get pointer 1))
                                   (k/& (az/get pointer 0)))))))

(az/deftest slice-arithmetic-test
  (let [numbers (k/var (az/array [1 2 3 4] :i32))
        length (k/var 0 :usize)]
    ;; Taking its address keeps length runtime-known.
    (k/= :_ (k/& length))
    (let [slice (k/var (az/slice numbers length (:len numbers)))]
      (try (testing/expectEqual 1 (az/get slice 0)))
      (try (testing/expectEqual 4 (:len slice)))
      (k/+= (:ptr slice) 1)
      ;; Deliberately inconsistent: moving ptr does not update len.
      (try (testing/expectEqual 2 (az/get slice 0)))
      (try (testing/expectEqual 4 (:len slice))))))

(comment
  (many-item-arithmetic-test)
  (slice-arithmetic-test))
