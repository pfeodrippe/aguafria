(ns learn.example.test-switch-modify-tagged-union
  (:require [aguafria.keyword :as ak]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defenum ResultTag
  [:ok
   :not_ok])

(az/defconst Result
  (az/union {:argument ResultTag}
    [[:ok :u8]
     [:not_ok :void]]))

(az/deftest mutate-tagged-payload-test
  (let [^:var result (az/init Result {:ok 42})]
    (az/switch-stmt result
      (case [(az/field ResultTag :ok)] [(az/pointer-capture value)]
        (az/block
          (ak/+= @value 1)))
      (case [(az/field ResultTag :not_ok)] (ak/unreachable)))
    (try (testing/expectEqual 43 (az/field result :ok)))))

(comment
  (mutate-tagged-payload-test))
