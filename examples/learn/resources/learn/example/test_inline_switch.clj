(ns learn.example.test-inline-switch
  (:require [aguafria.keyword :as ak]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Struct :as struct-info]
            [aguafria.std.builtin.Type.StructField :as field-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- isFieldOptional :!bool
  [[T {:attrs #{ak/comptime}} :type] [field-index :usize]]
  (let [fields (-> (ak/typeInfo T) type-info/-struct struct-info/-fields)]
    (ak/switch field-index
      ;; This prong is analyzed twice, with a compile-time-known index each time.
      (az/inline-case [0 1] [index]
        (ak/== (ak/typeInfo (field-info/-type (az/index fields index))) :.optional))
      (az/case-else
        (ak/return (az/error-value :IndexOutOfBounds))))))

(az/defstruct Struct1
  [[:a :u32]
   [:b [:optional :u32]]])

(az/deftest runtime-index-type-info-test
  (let [index (ak/var 0 :usize)]
    (try (testing/expect (ak/! (try (isFieldOptional Struct1 index)))))
    (ak/+= index 1)
    (try (testing/expect (try (isFieldOptional Struct1 index))))
    (ak/+= index 1)
    (try (testing/expectError (az/error-value :IndexOutOfBounds)
                              (isFieldOptional Struct1 index)))))

;; Calls to field-optional? on Struct1 unroll to the equivalent of this function.
(az/defn- isFieldOptionalUnrolled :!bool
  [[field-index :usize]]
  (ak/switch field-index
    (case [0] false)
    (case [1] true)
    (az/case-else
      (ak/return (az/error-value :IndexOutOfBounds)))))

(comment
  (runtime-index-type-info-test))
