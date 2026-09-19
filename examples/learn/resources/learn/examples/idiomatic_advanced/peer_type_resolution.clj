(ns learn.examples.idiomatic-advanced.peer-type-resolution
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest integer-widening-peers-test
  (let [^{:zig/type :i8} small 12
        ^{:zig/type :i16} wide 34
        sum (+ small wide)]
    (try (testing/expectEqual 46 sum))
    (try (testing/expectEqual :i16 (ak/TypeOf sum)))))

(az/deftest small-integer-and-float-peers-test
  ;; This only works for integer types that can coerce to the float type.
  ;; Larger integer types cause a compiler error; no float widening occurs.
  (let [^{:var :u8} integer 12
        ^{:var :f32} float 34]
    (set! _ [(& integer) (& float)])
    (let [sum (+ integer float)]
      (try (testing/expectEqual sum 46.0))
      (try (testing/expectEqual (ak/TypeOf sum) :f32)))))

(az/deftest differently-sized-array-peers-test
  (try (testing/expectEqualStrings "true" (bool-to-string true)))
  (try (testing/expectEqualStrings "false" (bool-to-string false)))
  (try (ak/comptime (testing/expectEqualStrings "true" (bool-to-string true))))
  (try (ak/comptime (testing/expectEqualStrings "false" (bool-to-string false)))))

(az/defn- bool-to-string [:slice-const :u8]
  [[value :bool]]
  (if value
    "true"
    "false"))

(az/deftest array-and-const-slice-peers-test
  (try (check-array-and-const-slice true))
  (try (ak/comptime (check-array-and-const-slice true))))

(az/defn- check-array-and-const-slice :void
  {:zig/qualifiers "!"}
  [[choose-first? :bool]]
  (let [first-value (if choose-first?
                      "aoeu"
                      (ak/as (az/type [:slice-const :u8]) "zz"))
        second-value (if choose-first?
                       (ak/as (az/type [:slice-const :u8]) "zz")
                       "aoeu")]
    (try (testing/expectEqualStrings "aoeu" first-value))
    (try (testing/expectEqualStrings "zz" second-value))))

(az/deftest value-and-optional-peers-test
  (try (testing/expectEqual 0 (az/unwrap (value-and-optional true false))))
  (try (testing/expectEqual 3 (az/unwrap (value-and-optional false false))))
  (az/comptime-stmt
    (az/block
      (try (testing/expectEqual 0 (az/unwrap (value-and-optional true false))))
      (try (testing/expectEqual 3 (az/unwrap (value-and-optional false false)))))))

(az/defn- value-and-optional [:optional :usize]
  [[choose-optional? :bool] [choose-null? :bool]]
  (when choose-optional?
    (ak/return
      (if choose-null?
        nil
        (ak/as :usize 0))))
  (ak/as :usize 3))

(az/deftest empty-array-and-slice-peers-test
  (try (testing/expectEqual 0 (az/field (empty-array-or-slice true "hi") :len)))
  (try (testing/expectEqual 1 (az/field (empty-array-or-slice false "hi") :len)))
  (az/comptime-stmt
    (az/block
      (try (testing/expectEqual 0 (az/field (empty-array-or-slice true "hi") :len)))
      (try (testing/expectEqual 1 (az/field (empty-array-or-slice false "hi") :len))))))

(az/defn- empty-array-or-slice [:slice-const :u8]
  [[choose-empty? :bool] [slice [:slice-const :u8]]]
  (when choose-empty?
    (ak/return (& (az/array-init [:array _ :u8] []))))
  (az/slice slice 0 1))

(az/deftest empty-array-slice-and-error-peers-test
  (let [^:var data @"hi"
        slice (az/slice data 0)]
    (try (testing/expectEqual 0 (az/field (try (empty-array-or-slice-or-error true slice)) :len)))
    (try (testing/expectEqual 1 (az/field (try (empty-array-or-slice-or-error false slice)) :len))))
  (az/comptime-stmt
    (let [^:var data @"hi"
          slice (az/slice data 0)]
      (try (testing/expectEqual 0 (az/field (try (empty-array-or-slice-or-error true slice)) :len)))
      (try (testing/expectEqual 1 (az/field (try (empty-array-or-slice-or-error false slice)) :len))))))

(az/defn- empty-array-or-slice-or-error [:error-union :anyerror [:slice :u8]]
  [[choose-empty? :bool] [slice [:slice :u8]]]
  (when choose-empty?
    (ak/return (& (az/array-init [:array _ :u8] []))))
  (az/slice slice 0 1))

(az/deftest const-pointer-and-optional-pointer-peers-test
  (let [^{:zig/type [:*const :usize]} constant-pointer (ak/ptrFromInt 0x123456780)
        ^{:zig/type [:optional [:* :usize]]} optional-pointer (ak/ptrFromInt 0x123456780)]
    (try (testing/expectEqual constant-pointer optional-pointer))
    (try (testing/expectEqual optional-pointer constant-pointer))))

(az/deftest error-union-switch-peers-test
  ;; The successful and error branches are peers only when the error branch
  ;; is a direct switch expression. Wrapping its switch in a labeled block
  ;; would prevent peer type resolution across those branches.
  (let [^{:var [:error-union [:error-set [:A :B :C]] :u32]} result 0]
    (set! _ (& result))
    (let [from-if (az/if-capture {:payload [value] :error [error]} result
                    (+ value 3)
                    (ak/switch error
                      (case [(az/error-value :A)] 0)
                      (case [(az/error-value :B)] 1)
                      (case [(az/error-value :C)] nil)))]
      (try (testing/expectEqual (az/type [:optional :u32]) (ak/TypeOf from-if))))

    ;; The same direct-switch requirement applies to catch: a labeled block
    ;; would prevent the unwrapped value and error cases from being peers.
    (let [from-catch (az/catch-capture [error] result
                       (ak/switch error
                         (case [(az/error-value :A)] 0)
                         (case [(az/error-value :B)] 1)
                         (case [(az/error-value :C)] nil)))]
      (try (testing/expectEqual (az/type [:optional :u32]) (ak/TypeOf from-catch))))))
