(ns learn.example.test-inline-switch
  (:require [aguafria.keyword :as k]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Struct :as struct-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- isFieldOptional :!bool
  [[T {:attrs #{k/comptime}} :type] [field-index :usize]]
  (let [field-types (-> (k/typeInfo T) type-info/-struct struct-info/-field_types)]
    (k/switch field-index
      ;; This prong is analyzed twice with `idx` being a
      ;; comptime-known value each time.
              (a/inline-case [0 1] [idx]
                             (k/== (k/typeInfo (a/get field-types idx)) :.optional))
              (a/case-else
               (k/return (a/error-value :IndexOutOfBounds))))))

(a/defstruct Struct1
  [[:a :u32]
   [:b [:optional :u32]]])

(a/deftest using-typeInfo-with-runtime-values
  (let [index (k/var 0 :usize)]
    (try (testing/expect (k/! (try (isFieldOptional Struct1 index)))))
    (k/+= index 1)
    (try (testing/expect (try (isFieldOptional Struct1 index))))
    (k/+= index 1)
    (try (testing/expectError (a/error-value :IndexOutOfBounds)
                              (isFieldOptional Struct1 index)))))

;; Calls to `isFieldOptional` on `Struct1` get unrolled to an equivalent
;; of this function:
(a/defn- isFieldOptionalUnrolled :!bool
  [[field-index :usize]]
  (k/switch field-index
            (case [0] false)
            (case [1] true)
            (a/case-else
             (k/return (a/error-value :IndexOutOfBounds)))))

(comment
  (using-typeInfo-with-runtime-values))
