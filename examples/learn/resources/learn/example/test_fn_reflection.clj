(ns learn.example.test-fn-reflection
  (:require [aguafria.keyword :as ak]
            [aguafria.std.math :as math]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/deftest function-reflection-test
  (let [expect-signature (az/field (ak/typeInfo (ak/TypeOf testing/expect)) :fn)
        first-parameter (az/index (az/field expect-signature :params) 0)
        tmp-dir-signature (az/field (ak/typeInfo (ak/TypeOf testing/tmpDir)) :fn)
        log2-signature (az/field (ak/typeInfo (ak/TypeOf math/Log2Int)) :fn)]
    (try (testing/expectEqual :bool (az/unwrap (az/field first-parameter :type))))
    (try (testing/expectEqual testing/TmpDir
                              (az/unwrap (az/field tmp-dir-signature :return_type))))
    (try (testing/expect (az/field log2-signature :is_generic)))))
