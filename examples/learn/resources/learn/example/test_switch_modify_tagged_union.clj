(ns learn.example.test-switch-modify-tagged-union
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defenum ComplexTypeTag
  [:ok
   :not_ok])

(a/defunion ComplexType {:type ComplexTypeTag}
  [[:ok :u8]
   [:not_ok :void]])

(a/deftest modify-tagged-union-in-switch
  (let [c (k/var (ComplexType {:ok 42}))]
    (switch c
      (:ok ComplexTypeTag)
      (a/with-block :_ [(* value)] (a/block
                                    (+= @value 1)))
      (:not_ok ComplexTypeTag) (unreachable))
    (k/try (testing/expectEqual 43 (:ok c)))))

(comment
  (modify-tagged-union-in-switch))
