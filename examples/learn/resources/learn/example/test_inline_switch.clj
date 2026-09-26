(ns learn.example.test-inline-switch
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Struct :as struct-info]
            [aguafria.std.builtin.Type.StructField :as field-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- isFieldOptional :!bool
  [[T {:attrs #{k/comptime}} :type] [field-index :usize]]
  (let [fields (-> (k/typeInfo T) type-info/-struct struct-info/-fields)]
    (k/switch field-index
      ;; This prong is analyzed twice with `idx` being a
      ;; comptime-known value each time.
              (az/inline-case [0 1] [idx]
                              (k/== (k/typeInfo (field-info/-type (az/get fields idx))) :.optional))
              (az/case-else
               (k/return (az/error-value :IndexOutOfBounds))))))

(az/defstruct Struct1
  [[:a :u32]
   [:b [:optional :u32]]])

(az/deftest using-typeInfo-with-runtime-values
  (let [index (k/var 0 :usize)]
    (try (testing/expect (k/! (try (isFieldOptional Struct1 index)))))
    (k/+= index 1)
    (try (testing/expect (try (isFieldOptional Struct1 index))))
    (k/+= index 1)
    (try (testing/expectError (az/error-value :IndexOutOfBounds)
                              (isFieldOptional Struct1 index)))))

;; Calls to `isFieldOptional` on `Struct1` get unrolled to an equivalent
;; of this function:
(az/defn- isFieldOptionalUnrolled :!bool
  [[field-index :usize]]
  (k/switch field-index
            (case [0] false)
            (case [1] true)
            (az/case-else
             (k/return (az/error-value :IndexOutOfBounds)))))

(comment
  (using-typeInfo-with-runtime-values))
