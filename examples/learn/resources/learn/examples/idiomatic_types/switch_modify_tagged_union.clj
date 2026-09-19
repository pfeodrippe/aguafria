(ns learn.examples.idiomatic-types.switch-modify-tagged-union
  (:require aguafria.std
            [aguafria.keyword :as ak]
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

(az/deftest mutate-tagged-payload-test
  (let [^:var result (az/init Result {:ok 42})]
    (az/switch-stmt result
      (case [(az/field ResultTag :ok)] [(az/pointer-capture value)]
        (az/block
          (ak/+= @value 1)))
      (case [(az/field ResultTag :not_ok)] (ak/unreachable)))
    (try (testing/expectEqual 43 (az/field result :ok)))))
