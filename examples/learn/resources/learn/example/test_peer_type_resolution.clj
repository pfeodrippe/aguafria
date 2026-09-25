(ns learn.example.test-peer-type-resolution
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- boolToStr [:slice-const :u8]
  [[value :bool]]
  (if value
    "true"
    "false"))

(az/defn- testPeerResolveArrayConstSlice :!void
  [[choose-first? :bool]]
  (let [first-value (if choose-first?
                      "aoeu"
                      (k/as "zz" (az/type [:slice-const :u8])))
        second-value (if choose-first?
                       (k/as "zz" (az/type [:slice-const :u8]))
                       "aoeu")]
    (try (testing/expectEqualStrings "aoeu" first-value))
    (try (testing/expectEqualStrings "zz" second-value))))

(az/defn- peerTypeTAndOptionalT [:optional :usize]
  [[choose-optional? :bool] [choose-null? :bool]]
  (when choose-optional?
    (k/return
     (if choose-null?
       nil
       (k/as 0 :usize))))
  (k/as 3 :usize))

(az/defn- peerTypeEmptyArrayAndSlice [:slice-const :u8]
  [[choose-empty? :bool] [slice [:slice-const :u8]]]
  (when choose-empty?
    (k/return (k/& (az/array-init [] [:array :_ :u8]))))
  (az/slice slice 0 1))

(az/defn- peerTypeEmptyArrayAndSliceAndError [:error-union :anyerror [:slice :u8]]
  [[choose-empty? :bool] [slice [:slice :u8]]]
  (when choose-empty?
    (k/return (k/& (az/array-init [] [:array :_ :u8]))))
  (az/slice slice 0 1))

(az/deftest integer-widening-peers-test
  (let [small (k/i8 12)
        wide (k/i16 34)
        sum (k/+ small wide)]
    (try (testing/expectEqual 46 sum))
    (try (testing/expectEqual :i16 (k/TypeOf sum)))))

(az/deftest small-integer-and-float-peers-test
  ;; This only works for integer types that can coerce to the float type.
  ;; Larger integer types cause a compiler error; no float widening occurs.
  (let [integer (k/var 12 :u8)
        float (k/var 34 :f32)]
    (k/= :_ [(k/& integer) (k/& float)])
    (let [sum (k/+ integer float)]
      (try (testing/expectEqual sum 46.0))
      (try (testing/expectEqual (k/TypeOf sum) :f32)))))

(az/deftest differently-sized-array-peers-test
  (try (testing/expectEqualStrings "true" (boolToStr true)))
  (try (testing/expectEqualStrings "false" (boolToStr false)))
  (try (k/comptime (testing/expectEqualStrings "true" (boolToStr true))))
  (try (k/comptime (testing/expectEqualStrings "false" (boolToStr false)))))

(az/deftest array-and-const-slice-peers-test
  (try (testPeerResolveArrayConstSlice true))
  (try (k/comptime (testPeerResolveArrayConstSlice true))))

(az/deftest value-and-optional-peers-test
  (try (testing/expectEqual 0 (az/unwrap (peerTypeTAndOptionalT true false))))
  (try (testing/expectEqual 3 (az/unwrap (peerTypeTAndOptionalT false false))))
  (az/comptime-stmt
    (az/block
      (try (testing/expectEqual 0 (az/unwrap (peerTypeTAndOptionalT true false))))
      (try (testing/expectEqual 3 (az/unwrap (peerTypeTAndOptionalT false false)))))))

(az/deftest empty-array-and-slice-peers-test
  (try (testing/expectEqual 0 (az/field (peerTypeEmptyArrayAndSlice true "hi") :len)))
  (try (testing/expectEqual 1 (az/field (peerTypeEmptyArrayAndSlice false "hi") :len)))
  (az/comptime-stmt
    (az/block
      (try (testing/expectEqual 0 (az/field (peerTypeEmptyArrayAndSlice true "hi") :len)))
      (try (testing/expectEqual 1 (az/field (peerTypeEmptyArrayAndSlice false "hi") :len))))))

(az/deftest empty-array-slice-and-error-peers-test
  (let [data (k/var @"hi")
        slice (az/slice data 0)]
    (try (testing/expectEqual 0 (az/field (try (peerTypeEmptyArrayAndSliceAndError true slice)) :len)))
    (try (testing/expectEqual 1 (az/field (try (peerTypeEmptyArrayAndSliceAndError false slice)) :len))))
  (az/comptime-stmt
    (let [data (k/var @"hi")
          slice (az/slice data 0)]
      (try (testing/expectEqual 0 (az/field (try (peerTypeEmptyArrayAndSliceAndError true slice)) :len)))
      (try (testing/expectEqual 1 (az/field (try (peerTypeEmptyArrayAndSliceAndError false slice)) :len))))))

(az/deftest const-pointer-and-optional-pointer-peers-test
  (let [constant-pointer (k/as (k/ptrFromInt 0x123456780) [:*const :usize])
        optional-pointer (k/as (k/ptrFromInt 0x123456780) [:optional [:* :usize]])]
    (try (testing/expectEqual constant-pointer optional-pointer))
    (try (testing/expectEqual optional-pointer constant-pointer))))

(az/deftest error-union-switch-peers-test
  ;; The successful and error branches are peers only when the error branch
  ;; is a direct switch expression. Wrapping its switch in a labeled block
  ;; would prevent peer type resolution across those branches.
  (let [result (k/var 0 [:error-union [:error-set [:A :B :C]] :u32])]
    (k/= :_ (k/& result))
    (let [from-if (az/if-capture {:payload [value] :error [error]} result
                                 (k/+ value 3)
                                 (k/switch error
                                   (case [(az/error-value :A)] 0)
                                   (case [(az/error-value :B)] 1)
                                   (case [(az/error-value :C)] nil)))]
      (try (testing/expectEqual (az/type [:optional :u32]) (k/TypeOf from-if))))

    ;; The same direct-switch requirement applies to catch: a labeled block
    ;; would prevent the unwrapped value and error cases from being peers.
    (let [from-catch (az/catch-capture [error] result
                                       (k/switch error
                                         (case [(az/error-value :A)] 0)
                                         (case [(az/error-value :B)] 1)
                                         (case [(az/error-value :C)] nil)))]
      (try (testing/expectEqual (az/type [:optional :u32]) (k/TypeOf from-catch))))))

(comment
  (integer-widening-peers-test)
  (small-integer-and-float-peers-test)
  (differently-sized-array-peers-test)
  (array-and-const-slice-peers-test)
  (value-and-optional-peers-test)
  (empty-array-and-slice-peers-test)
  (empty-array-slice-and-error-peers-test)
  (const-pointer-and-optional-pointer-peers-test)
  (error-union-switch-peers-test))
