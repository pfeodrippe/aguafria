(ns aguafria.zig.jvm-scoped-result-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn overwrite-label :void [[value [:* :u64]]]
  (= @value 104))

(a/defstruct ApplicationMarker
  [[^{:zig/name "@\"aguafria.jvm/scoped-capture-mismatch\""} marker :bool]])

(a/deftest labeled-result
  (let [selector (u64 101)
        label (u64 103)
        result (switch selector
                 101
                 (a/with-block :__aguafria_binding_0
                   (let [number (u64 5)]
                     (break :__aguafria_binding_0 (+ (* number 2) 1))))
                 label label
                 9)]
    (try (testing/expectEqual 11 result))))

(a/deftest retained-label-result
  (let [selector (u64 103)
        label (u64 103)
        result (switch selector
                 label label
                 9)]
    (try (testing/expectEqual 103 result))))

(a/deftest application-error-result
  (let [selector (u64 4)
        label (u64 3)
        result (switch selector
                 label (as 0 [:error-union [:error-set [:AguafriaScopedCaptureValueChanged]] :u32])
                 4 (as (a/error-value :AguafriaScopedCaptureValueChanged)
                       [:error-union [:error-set [:AguafriaScopedCaptureValueChanged]] :u32])
                 (as 1 [:error-union [:error-set [:AguafriaScopedCaptureValueChanged]] :u32]))]
    (a/if-capture-stmt {:payload [payload] :error [err]} result
                       (a/block (= :_ payload) (unreachable))
                       (try (testing/expectEqual err (a/error-value :AguafriaScopedCaptureValueChanged))))))

(a/deftest retained-type-result
  (let [selector (u64 3)
        label (u64 3)
        result (switch selector
                 label (a/type :u16)
                 (a/type :u32))]
    (try (testing/expectEqual (a/type :u16) result))))

(a/deftest ordinary-runtime-result
  (let [selector (k/var 0 :u32)]
    (= selector 1)
    (let [result (switch selector
                   1 (u32 9)
                   (u32 8))]
      (try (testing/expectEqual 9 result)))))

(a/deftest mutation-tools
  (let [value (k/var 103 :u64)
        alias (k/& value)]
    (overwrite-label alias)
    (try (testing/expectEqual 104 value))))

(a/deftest application-marker-struct
  (let [result (a/with-block :owned
                 (break :owned
                        (as (a/raw "(.{ .@\"aguafria.jvm/scoped-capture-mismatch\" = true })")
                            ApplicationMarker)))]
    (= :_ result)
    (try (testing/expect true))))
