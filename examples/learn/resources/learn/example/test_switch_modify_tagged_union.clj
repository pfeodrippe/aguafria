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

(az/deftest modify-tagged-union-in-switch
  (let [c (k/var (ComplexType {:ok 42}))]
    (az/switch-stmt c
                    (case [(:ok ComplexTypeTag)] [(az/pointer-capture value)]
                          (az/block
                           (k/+= @value 1)))
                    (case [(:not_ok ComplexTypeTag)] (k/unreachable)))
    (try (testing/expectEqual 43 (:ok c)))))

(comment
  (modify-tagged-union-in-switch))
