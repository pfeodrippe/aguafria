(ns learn.example.test-inline-switch
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- field-optional? :bool
  {:zig/qualifiers "!"}
  [[T {:zig/prefix "comptime"} :type] [field-index :usize]]
  (let [fields (az/field (az/field (ak/typeInfo T) :struct) :fields)]
    (ak/switch field-index
      ;; This prong is analyzed twice, with a compile-time-known index each time.
      (az/inline-case [0 1] [index]
        (== (ak/typeInfo (az/field (az/index fields index) :type)) :.optional))
      (az/case-else
        (ak/return (az/error-value :IndexOutOfBounds))))))

(az/defstruct Struct1
  [[:a :u32]
   [:b [:optional :u32]]])

(az/deftest runtime-index-type-info-test
  (let [^{:var :usize} index 0]
    (try (testing/expect (ak/! (try (field-optional? Struct1 index)))))
    (ak/+= index 1)
    (try (testing/expect (try (field-optional? Struct1 index))))
    (ak/+= index 1)
    (try (testing/expectError (az/error-value :IndexOutOfBounds)
                              (field-optional? Struct1 index)))))

;; Calls to field-optional? on Struct1 unroll to the equivalent of this function.
(az/defn- field-optional-unrolled? :bool
  {:zig/qualifiers "!"}
  [[field-index :usize]]
  (ak/switch field-index
    (case [0] false)
    (case [1] true)
    (az/case-else
      (ak/return (az/error-value :IndexOutOfBounds)))))
