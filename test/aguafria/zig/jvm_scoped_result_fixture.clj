(ns aguafria.zig.jvm-scoped-result-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn overwrite-label :void [[value [:* :u64]]]
  (k/= @value 104))

(a/defstruct ApplicationMarker
  [[^{:zig/name "@\"aguafria.jvm/scoped-capture-mismatch\""} marker :bool]])

(a/deftest labeled-result
  (let [selector (k/u64 101)
        label (k/u64 103)
        result (k/switch selector
                         (case [101]
                           (a/with-block :__aguafria_binding_0
                             (let [number (k/u64 5)]
                               (k/break :__aguafria_binding_0 (k/+ (k/* number 2) 1)))))
                         (case [label] label)
                         (a/case-else 9))]
    (try (testing/expectEqual 11 result))))

(a/deftest retained-label-result
  (let [selector (k/u64 103)
        label (k/u64 103)
        result (k/switch selector
                         (case [label] label)
                         (a/case-else 9))]
    (try (testing/expectEqual 103 result))))

(a/deftest application-error-result
  (let [selector (k/u64 4)
        label (k/u64 3)
        result (k/switch selector
                         (case [label] (k/as 0 [:error-union [:error-set [:AguafriaScopedCaptureValueChanged]] :u32]))
                         (case [4] (k/as (a/error-value :AguafriaScopedCaptureValueChanged)
                                         [:error-union [:error-set [:AguafriaScopedCaptureValueChanged]] :u32]))
                         (a/case-else (k/as 1 [:error-union [:error-set [:AguafriaScopedCaptureValueChanged]] :u32])))]
    (a/if-capture-stmt {:payload [payload] :error [err]} result
                       (a/block (k/= :_ payload) (k/unreachable))
                       (try (testing/expectEqual err (a/error-value :AguafriaScopedCaptureValueChanged))))))

(a/deftest retained-type-result
  (let [selector (k/u64 3)
        label (k/u64 3)
        result (k/switch selector
                         (case [label] (a/type :u16))
                         (a/case-else (a/type :u32)))]
    (try (testing/expectEqual (a/type :u16) result))))

(a/deftest ordinary-runtime-result
  (let [selector (k/var 0 :u32)]
    (k/= selector 1)
    (let [result (k/switch selector
                           (case [1] (k/u32 9))
                           (a/case-else (k/u32 8)))]
      (try (testing/expectEqual 9 result)))))

(a/deftest mutation-tools
  (let [value (k/var 103 :u64)
        alias (k/& value)]
    (overwrite-label alias)
    (try (testing/expectEqual 104 value))))

(a/deftest application-marker-struct
  (let [result (a/with-block :owned
                 (k/break :owned
                          (k/as (a/raw "(.{ .@\"aguafria.jvm/scoped-capture-mismatch\" = true })")
                                ApplicationMarker)))]
    (k/= :_ result)
    (try (testing/expect true))))
