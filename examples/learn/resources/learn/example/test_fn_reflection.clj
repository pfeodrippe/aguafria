(ns learn.example.test-fn-reflection
  (:require [aguafria.keyword :as k]
            [aguafria.std.builtin.Type :as type-info]
            [aguafria.std.builtin.Type.Fn :as fn-info]
            [aguafria.std.builtin.Type.Fn.Param :as param-info]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest function-reflection-test
  (let [expect-signature (type-info/-fn (k/typeInfo (k/TypeOf testing/expect)))
        first-parameter (az/index (fn-info/-params expect-signature) 0)
        tmp-dir-signature (type-info/-fn (k/typeInfo (k/TypeOf testing/tmpDir)))
        log2-signature (type-info/-fn (k/typeInfo (k/TypeOf math/Log2Int)))]
    (try (testing/expectEqual :bool (az/unwrap (param-info/-type first-parameter))))
    (try (testing/expectEqual testing/TmpDir
                              (az/unwrap (fn-info/-return_type tmp-dir-signature))))
    (try (testing/expect (fn-info/-is_generic log2-signature)))))

(comment
  (function-reflection-test))
