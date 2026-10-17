(ns learn.example.test-TypeOf-builtin
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defn- foo T
  [[T {:attrs #{comptime}} :type] [ptr [:* T]]]
  (+= @ptr 1)
  @ptr)

(a/deftest no-runtime-side-effects
  (let [data (k/var 0 :i32)
        T (TypeOf (foo :i32 (k/& data)))]
    (k/try (comptime (testing/expectEqual :i32 T)))
    (k/try (testing/expectEqual 0 data))))

(comment
  (no-runtime-side-effects))
