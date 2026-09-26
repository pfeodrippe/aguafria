(ns learn.example.test-inline-else
  (:require [aguafria.keyword :as k]
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
  [[any AnySlice]]
  (let [Tag (az/unwrap (-> (k/typeInfo AnySlice) type-info/-union union-info/-tag_type))]
    (az/inline-for [field (-> (k/typeInfo Tag) type-info/-enum enum-info/-fields)]
      ;; With `inline for` the function gets generated as
      ;; a series of `if` statements relying on the optimizer
      ;; to convert it to a switch.
                   (when (k/== (field-info/-value field) (k/intFromEnum any))
                     (k/return (:len (k/field any (field-info/-name field)))))))
  ;; When using `inline for` the compiler doesn't know that every
  ;; possible case has been handled requiring an explicit `unreachable`.
  (k/unreachable))

(az/defn- with-switch :usize
  [[any AnySlice]]
  (k/switch any
    ;; With `inline else` the function is explicitly generated
    ;; as the desired switch and the compiler can check that
    ;; every possible case is handled.
            (az/inline-case-else [slice]
                                 (:len slice))))

(az/deftest inline-for-and-inline-else-similarity
  (let [any (AnySlice {:c "hello"})]
    (try (testing/expectEqual 5 (with-for any)))
    (try (testing/expectEqual 5 (with-switch any)))))

(comment
  (inline-for-and-inline-else-similarity))
