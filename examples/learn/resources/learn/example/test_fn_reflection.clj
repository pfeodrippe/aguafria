(ns learn.example.test-fn-reflection
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Fn :as fn-info]
            [aguafria.std.builtin.Type.Fn.Param :as param-info]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest fn-reflection
  (try (testing/expectEqual
        :bool
        (az/unwrap (param-info/-type
                    (az/get (fn-info/-params (type-info/-fn (k/typeInfo (k/TypeOf testing/expect)))) 0)))))
  (try (testing/expectEqual
        testing/TmpDir
        (az/unwrap (fn-info/-return_type (type-info/-fn (k/typeInfo (k/TypeOf testing/tmpDir)))))))
  (try (testing/expect (fn-info/-is_generic (type-info/-fn (k/typeInfo (k/TypeOf math/Log2Int)))))))

(comment
  (fn-reflection))
