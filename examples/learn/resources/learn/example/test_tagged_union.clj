(ns learn.example.test-tagged-union
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.std.meta :as meta]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum ComplexTypeTag
  [:ok
   :not_ok])

(az/defconst ComplexType
  (az/union {:argument ComplexTypeTag}
    [[:ok :u8]
     [:not_ok :void]]))

(az/deftest tagged-union-switch-test
  (let [result (az/init {:ok 42} ComplexType)]
    (try (testing/expectEqual (az/field ComplexTypeTag :ok) (ak/as result ComplexTypeTag)))
    (az/switch-stmt result
      (case [:.ok] [value] (try (testing/expectEqual 42 value)))
      (case [:.not_ok] (ak/unreachable)))
    (az/switch-stmt result
      ;; A tag captured by a single prong is known at compile time.
      (case [:.ok] [_ tag]
        (ak/comptime (debug/assert (ak/== tag :.ok))))
      (case [:.not_ok] (ak/unreachable)))))

(az/deftest tag-type-test
  (try (testing/expectEqual ComplexTypeTag (meta/Tag ComplexType))))

(comment
  (tagged-union-switch-test)
  (tag-type-test))
