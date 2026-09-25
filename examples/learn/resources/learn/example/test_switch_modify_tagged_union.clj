(ns learn.example.test-switch-modify-tagged-union
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum ComplexTypeTag
  [:ok
   :not_ok])

(az/defconst ComplexType
  (az/union {:argument ComplexTypeTag}
    [[:ok :u8]
     [:not_ok :void]]))

(az/deftest mutate-tagged-payload-test
  (let [result (ak/var (ComplexType {:ok 42}))]
    (az/switch-stmt result
      (case [(az/field ComplexTypeTag :ok)] [(az/pointer-capture value)]
        (az/block
          (ak/+= @value 1)))
      (case [(az/field ComplexTypeTag :not_ok)] (ak/unreachable)))
    (try (testing/expectEqual 43 (az/field result :ok)))))

(comment
  (mutate-tagged-payload-test))
