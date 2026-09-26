(ns learn.example.test-TypeOf-builtin
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defn- foo T
  [[T {:attrs #{k/comptime}} :type] [ptr [:* T]]]
  (k/+= @ptr 1)
  @ptr)

(az/deftest no-runtime-side-effects
  (let [data (k/var 0 :i32)
        T (k/TypeOf (foo :i32 (k/& data)))]
    (try (k/comptime (testing/expectEqual :i32 T)))
    (try (testing/expectEqual 0 data))))

(comment
  (no-runtime-side-effects))
