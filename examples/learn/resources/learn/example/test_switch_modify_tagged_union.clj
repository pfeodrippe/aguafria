(ns learn.example.test-switch-modify-tagged-union
  (:require [aguafria.keyword :as k]
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
  (let [result (k/var (ComplexType {:ok 42}))]
    (az/switch-stmt result
      (case [(:ok ComplexTypeTag)] [(az/pointer-capture value)]
        (az/block
          (k/+= @value 1)))
      (case [(:not_ok ComplexTypeTag)] (k/unreachable)))
    (try (testing/expectEqual 43 (:ok result)))))

(comment
  (mutate-tagged-payload-test))
