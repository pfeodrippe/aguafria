(ns learn.examples.idiomatic-values.typeof-builtin
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest no-runtime-side-effects-test
  (let [^{:var :i32} data 0
        T (ak/TypeOf (increment-pointed-value :i32 (& data)))]
    (try (ak/comptime (testing/expectEqual :i32 T)))
    (try (testing/expectEqual 0 data))))

(az/defn- increment-pointed-value T
  [[T {:zig/prefix "comptime"} :type] [pointer [:* T]]]
  (ak/+= @pointer 1)
  @pointer)
