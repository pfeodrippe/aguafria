(ns learn.example.test-fn-reflection
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.lang.Type :as type-info]
            [aguafria.std.lang.Type.Fn :as fn-info]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/deftest fn-reflection
  (k/try (testing/expectEqual
          :bool
          (a/unwrap (a/get (fn-info/-param_types
                            (type-info/-fn (typeInfo (TypeOf testing/expect)))) 0))))
  (k/try (testing/expectEqual
          testing/TmpDir
          (a/unwrap (fn-info/-return_type (type-info/-fn (typeInfo (TypeOf testing/tmpDir)))))))
  (k/try (testing/expect (fn-info/-is_generic (type-info/-fn (typeInfo (TypeOf math/Log2Int)))))))

(comment
  (fn-reflection))
