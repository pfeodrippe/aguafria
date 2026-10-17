(ns learn.example.test-inline-switch
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Struct :as struct-info]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- isFieldOptional :!bool
  [[T {:attrs #{comptime}} :type] [field-index :usize]]
  (let [field-types (-> (typeInfo T) type-info/-struct struct-info/-field_types)]
    (switch field-index
      ;; This prong is analyzed twice with `idx` being a
      ;; comptime-known value each time.
      (0 1)
      (inline (a/with-block :_ [idx] (== (typeInfo (a/get field-types idx)) :optional)))

      (return (a/error-value :IndexOutOfBounds)))))

(a/defstruct Struct1
  [[:a :u32]
   [:b [:optional :u32]]])

(a/deftest using-typeInfo-with-runtime-values
  (let [index (k/var 0 :usize)]
    (k/try (testing/expect (! (k/try (isFieldOptional Struct1 index)))))
    (+= index 1)
    (k/try (testing/expect (k/try (isFieldOptional Struct1 index))))
    (+= index 1)
    (k/try (testing/expectError (a/error-value :IndexOutOfBounds)
                                (isFieldOptional Struct1 index)))))

;; Calls to `isFieldOptional` on `Struct1` get unrolled to an equivalent
;; of this function:
(a/defn- isFieldOptionalUnrolled :!bool
  [[field-index :usize]]
  (switch field-index
    0 false
    1 true

    (return (a/error-value :IndexOutOfBounds))))

(comment
  (using-typeInfo-with-runtime-values))
