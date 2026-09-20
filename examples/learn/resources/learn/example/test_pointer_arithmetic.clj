(ns learn.example.test-pointer-arithmetic
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest many-item-arithmetic-test
  (let [numbers (az/array-init [:array _ :i32] [1 2 3 4])
        ^{:var [:many-const :i32]} pointer (& numbers)]
    (try (testing/expectEqual 1 (az/index pointer 0)))
    (ak/+= pointer 1)
    (try (testing/expectEqual 2 (az/index pointer 0)))
    ;; An open-ended slice of a many-item pointer advances the pointer.
    (try (testing/expectEqual (az/slice pointer 1) (+ pointer 1)))
    ;; Pointer subtraction counts elements, not bytes.
    (try (testing/expectEqual 1 (- (& (az/index pointer 1))
                                   (& (az/index pointer 0)))))))

(az/deftest slice-arithmetic-test
  (let [^:var numbers (az/array-init [:array _ :i32] [1 2 3 4])
        ^{:var :usize} length 0]
    ;; Taking its address keeps length runtime-known.
    (set! _ (& length))
    (let [^:var slice (az/slice numbers length (az/field numbers :len))]
      (try (testing/expectEqual 1 (az/index slice 0)))
      (try (testing/expectEqual 4 (az/field slice :len)))
      (ak/+= (az/field slice :ptr) 1)
      ;; Deliberately inconsistent: moving ptr does not update len.
      (try (testing/expectEqual 2 (az/index slice 0)))
      (try (testing/expectEqual 4 (az/field slice :len))))))

(comment
  (many-item-arithmetic-test)
  (slice-arithmetic-test))
