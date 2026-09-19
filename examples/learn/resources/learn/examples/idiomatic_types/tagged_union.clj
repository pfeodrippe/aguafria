(ns learn.examples.idiomatic-types.tagged-union
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.std.meta :as meta]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst ResultTag
  (az/container {:kind :enum}
    (az/enum-field-decl :ok)
    (az/enum-field-decl :not_ok)))

(az/defconst Result
  (az/container {:kind :union :argument ResultTag}
    (az/field-decl :ok :u8)
    (az/field-decl :not_ok :void)))

(az/deftest tagged-union-switch-test
  (let [result (az/init Result {:ok 42})]
    (try (testing/expectEqual (az/field ResultTag :ok) (ak/as ResultTag result)))
    (az/switch-stmt result
      (case [:.ok] [value] (try (testing/expectEqual 42 value)))
      (case [:.not_ok] (ak/unreachable)))
    (az/switch-stmt result
      ;; A tag captured by a single prong is known at compile time.
      (case [:.ok] [_ tag]
        (ak/comptime (debug/assert (== tag :.ok))))
      (case [:.not_ok] (ak/unreachable)))))

(az/deftest tag-type-test
  (try (testing/expectEqual ResultTag (meta/Tag Result))))
