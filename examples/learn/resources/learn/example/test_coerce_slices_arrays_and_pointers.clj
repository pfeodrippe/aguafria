(ns learn.example.test-coerce-slices-arrays-and-pointers
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest constant-array-to-slice-test
  (let [literal (ak/as "hello" [:slice-const :u8])
        letters
        (ak/as (& (az/array-init [\h \e \l \l 111] [:array 5 :u8])) [:slice-const :u8])
        numbers
        (ak/as (& (az/array-init [1.2 3.4] [:array 2 :f32])) [:slice-const :f32])]
    (try (testing/expectEqualStrings literal letters))
    (try (testing/expectEqual 1.2 (az/index numbers 0)))))

(az/deftest constant-array-to-error-slice-test
  (let [literal (ak/as "hello" [:error-union :anyerror [:slice-const :u8]])
        letters
        (ak/as (& (az/array-init [\h \e \l \l 111] [:array 5 :u8])) [:error-union :anyerror [:slice-const :u8]])
        numbers
        (ak/as (& (az/array-init [1.2 3.4] [:array 2 :f32])) [:error-union :anyerror [:slice-const :f32]])]
    (try (testing/expectEqualStrings (try literal) (try letters)))
    (try (testing/expectEqual 1.2 (az/index (try numbers) 0)))))

(az/deftest constant-array-to-optional-slice-test
  (let [literal (ak/as "hello" [:optional [:slice-const :u8]])
        letters
        (ak/as (& (az/array-init [\h \e \l \l 111] [:array 5 :u8])) [:optional [:slice-const :u8]])
        numbers
        (ak/as (& (az/array-init [1.2 3.4] [:array 2 :f32])) [:optional [:slice-const :f32]])]
    (try (testing/expectEqualStrings (az/unwrap literal) (az/unwrap letters)))
    (try (testing/expectEqual 1.2 (az/index (az/unwrap numbers) 0)))))

(az/deftest array-to-slice-test
  ;; The fixed array length becomes the slice length during coercion.
  (let [buffer (ak/var (deref "hello") [:array 5 :u8])
        bytes (ak/as (& buffer) [:slice :u8])
        numbers (az/array-init [1.2 3.4] [:array 2 :f32])
        values (ak/as (& numbers) [:slice-const :f32])]
    (try (testing/expectEqualStrings "hello" bytes))
    (try (testing/expectEqualSlices
          (az/type :f32)
          (& (az/array-init [1.2 3.4] [:array 2 :f32]))
          values))))

(az/deftest array-to-many-pointer-test
  (let [buffer (ak/var (deref "hello") [:array 5 :u8])
        pointer (ak/as (& buffer) [:many :u8])]
    ;; A many-item pointer carries no length: index 5 would not be checked.
    (try (testing/expectEqual \o (az/index pointer 4)))))

(az/deftest array-to-optional-many-pointer-test
  (let [buffer (ak/var (deref "hello") [:array 5 :u8])
        pointer (ak/as (& buffer) [:optional [:many :u8]])]
    (try (testing/expectEqual \o (az/index (az/unwrap pointer) 4)))))

(az/deftest single-item-to-array-pointer-test
  (let [value (ak/var 1234 :i32)
        array-pointer (ak/as (& value) [:* [:array 1 :i32]])
        many-pointer (ak/as array-pointer [:many :i32])]
    (try (testing/expectEqual 1234 (az/index many-pointer 0)))))

(az/deftest sentinel-slice-to-pointer-test
  (let [slice (ak/as "hello" [:pointer {:sentinel 0, :size :slice, :const? true} :u8])
        pointer (ak/as slice [:sentinel-const :u8 0])]
    (try (testing/expectEqual \o (az/index pointer 4)))))

(comment
  (constant-array-to-slice-test)
  (constant-array-to-error-slice-test)
  (constant-array-to-optional-slice-test)
  (array-to-slice-test)
  (array-to-many-pointer-test)
  (array-to-optional-many-pointer-test)
  (single-item-to-array-pointer-test)
  (sentinel-slice-to-pointer-test))
