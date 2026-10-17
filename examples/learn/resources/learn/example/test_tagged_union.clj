(ns learn.example.test-tagged-union
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.std.meta :as meta]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defenum ComplexTypeTag
  [:ok
   :not_ok])

(a/defunion ComplexType {:type ComplexTypeTag}
  [[:ok :u8]
   [:not_ok :void]])

(a/deftest switch-on-tagged-union
  (let [c (ComplexType {:ok 42})]
    (k/try (testing/expectEqual (:ok ComplexTypeTag) (as c ComplexTypeTag)))
    (switch c
      :ok (a/with-block :_ [value] (k/try (testing/expectEqual 42 value)))
      :not_ok (unreachable))
    (switch c
      :ok
      ;; Because we're in the '.ok' prong, 'tag' is compile-time known to be '.ok':
      (a/with-block :_ [_ tag] (comptime (debug/assert (== tag :ok))))
      :not_ok (unreachable))))

(a/deftest get-tag-type
  (k/try (testing/expectEqual ComplexTypeTag (meta/Tag ComplexType))))

(comment
  (switch-on-tagged-union)
  (get-tag-type))
