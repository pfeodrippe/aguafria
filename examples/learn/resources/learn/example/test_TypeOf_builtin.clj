(ns learn.example.test-TypeOf-builtin
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- foo T
  [[T {:attrs #{ak/comptime}} :type] [pointer [:* T]]]
  (ak/+= @pointer 1)
  @pointer)

(az/deftest no-runtime-side-effects-test
  (let [data (ak/var 0 :i32)
        T (ak/TypeOf (foo :i32 (& data)))]
    (try (ak/comptime (testing/expectEqual :i32 T)))
    (try (testing/expectEqual 0 data))))

(comment
  (no-runtime-side-effects-test))
