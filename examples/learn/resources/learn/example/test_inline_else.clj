(ns learn.example.test-inline-else
  (:require [aguafria.keyword :as ak]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Union :as union-info]
            [aguafria.std.builtin.Type.Enum :as enum-info]
            [aguafria.std.builtin.Type.EnumField :as field-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defstruct SliceTypeA
  {:layout :extern}
  [[:len :usize]
   [:ptr [:many :u32]]])

(az/defstruct SliceTypeB
  {:layout :extern}
  [[:ptr [:many SliceTypeA]]
   [:len :usize]])

(az/defconst AnySlice
  (az/union {:enum? true}
    [[:a SliceTypeA]
     [:b SliceTypeB]
     [:c [:slice-const :u8]]
     [:d [:slice AnySlice]]]))

(az/defn- with-for :usize
  [[any-slice AnySlice]]
  (let [Tag (az/unwrap (-> (ak/typeInfo AnySlice) type-info/-union union-info/-tag_type))]
    (az/inline-for [field (-> (ak/typeInfo Tag) type-info/-enum enum-info/-fields)]
      ;; Inline for generates a series of if statements, relying on the
      ;; optimizer to convert them into a switch.
      (when (ak/== (field-info/-value field) (ak/intFromEnum any-slice))
        (ak/return (az/field (ak/field any-slice (field-info/-name field)) :len)))))
  ;; With inline for, the compiler does not know that every possible case
  ;; has been handled, so an explicit unreachable is required.
  (ak/unreachable))

(az/defn- with-switch :usize
  [[any-slice AnySlice]]
  (ak/switch any-slice
    ;; Inline else directly generates the desired switch, and the compiler
    ;; can check that every possible case is handled.
    (az/inline-case-else [slice]
      (az/field slice :len))))

(az/deftest inline-for-and-else-test
  (let [any-slice (AnySlice {:c "hello"})]
    (try (testing/expectEqual 5 (with-for any-slice)))
    (try (testing/expectEqual 5 (with-switch any-slice)))))

(comment
  (inline-for-and-else-test))
