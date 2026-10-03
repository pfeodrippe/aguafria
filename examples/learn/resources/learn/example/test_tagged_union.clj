(ns learn.example.test-tagged-union
  (:require [aguafria.keyword :as k]
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
    (try (testing/expectEqual (:ok ComplexTypeTag) (k/as c ComplexTypeTag)))
    (a/switch-stmt c
                   (case [:.ok] [value] (try (testing/expectEqual 42 value)))
                   (case [:.not_ok] (k/unreachable)))
    (a/switch-stmt c
                   (case [:.ok] [_ tag]
        ;; Because we're in the '.ok' prong, 'tag' is compile-time known to be '.ok':
                         (k/comptime (debug/assert (k/== tag :.ok))))
                   (case [:.not_ok] (k/unreachable)))))

(a/deftest get-tag-type
  (try (testing/expectEqual ComplexTypeTag (meta/Tag ComplexType))))

(comment
  (switch-on-tagged-union)
  (get-tag-type))
