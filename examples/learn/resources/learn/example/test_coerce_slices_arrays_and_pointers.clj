(ns learn.example.test-coerce-slices-arrays-and-pointers
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest constant-array-to-slice-test
  (let [^{:zig/type [:slice-const :u8]} literal "hello"
        ^{:zig/type [:slice-const :u8]} letters
        (& (az/array-init [:array 5 :u8] [\h \e \l \l 111]))
        ^{:zig/type [:slice-const :f32]} numbers
        (& (az/array-init [:array 2 :f32] [1.2 3.4]))]
    (try (testing/expectEqualStrings literal letters))
    (try (testing/expectEqual 1.2 (az/index numbers 0)))))

(az/deftest constant-array-to-error-slice-test
  (let [^{:zig/type [:error-union :anyerror [:slice-const :u8]]} literal "hello"
        ^{:zig/type [:error-union :anyerror [:slice-const :u8]]} letters
        (& (az/array-init [:array 5 :u8] [\h \e \l \l 111]))
        ^{:zig/type [:error-union :anyerror [:slice-const :f32]]} numbers
        (& (az/array-init [:array 2 :f32] [1.2 3.4]))]
    (try (testing/expectEqualStrings (try literal) (try letters)))
    (try (testing/expectEqual 1.2 (az/index (try numbers) 0)))))

(az/deftest constant-array-to-optional-slice-test
  (let [^{:zig/type [:optional [:slice-const :u8]]} literal "hello"
        ^{:zig/type [:optional [:slice-const :u8]]} letters
        (& (az/array-init [:array 5 :u8] [\h \e \l \l 111]))
        ^{:zig/type [:optional [:slice-const :f32]]} numbers
        (& (az/array-init [:array 2 :f32] [1.2 3.4]))]
    (try (testing/expectEqualStrings (az/unwrap literal) (az/unwrap letters)))
    (try (testing/expectEqual 1.2 (az/index (az/unwrap numbers) 0)))))

(az/deftest array-to-slice-test
  ;; The fixed array length becomes the slice length during coercion.
  (let [^{:var [:array 5 :u8]} buffer (deref "hello")
        ^{:zig/type [:slice :u8]} bytes (& buffer)
        numbers (az/array-init [:array 2 :f32] [1.2 3.4])
        ^{:zig/type [:slice-const :f32]} values (& numbers)]
    (try (testing/expectEqualStrings "hello" bytes))
    (try (testing/expectEqualSlices
          (az/type :f32)
          (& (az/array-init [:array 2 :f32] [1.2 3.4]))
          values))))

(az/deftest array-to-many-pointer-test
  (let [^{:var [:array 5 :u8]} buffer (deref "hello")
        ^{:zig/type [:many :u8]} pointer (& buffer)]
    ;; A many-item pointer carries no length: index 5 would not be checked.
    (try (testing/expectEqual \o (az/index pointer 4)))))

(az/deftest array-to-optional-many-pointer-test
  (let [^{:var [:array 5 :u8]} buffer (deref "hello")
        ^{:zig/type [:optional [:many :u8]]} pointer (& buffer)]
    (try (testing/expectEqual \o (az/index (az/unwrap pointer) 4)))))

(az/deftest single-item-to-array-pointer-test
  (let [^{:var :i32} value 1234
        ^{:zig/type [:* [:array 1 :i32]]} array-pointer (& value)
        ^{:zig/type [:many :i32]} many-pointer array-pointer]
    (try (testing/expectEqual 1234 (az/index many-pointer 0)))))

(az/deftest sentinel-slice-to-pointer-test
  (let [^{:zig/type [:pointer {:size :slice :const? true :sentinel 0} :u8]}
        slice "hello"
        ^{:zig/type [:sentinel-const :u8 0]} pointer slice]
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
