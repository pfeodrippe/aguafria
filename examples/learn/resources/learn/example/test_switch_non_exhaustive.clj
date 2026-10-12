(ns learn.example.test-switch-non-exhaustive
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(ns-unmap *ns* 'Number)

(a/defenum Number
  {:type :u8}
  [:one
   :two
   :three
   :_])

(a/deftest switch-on-non-exhaustive-enum
  (let [number (:one Number)
        result (k/switch number
                 :one true
                 (:two :three) false
                 _ false)]
    (try (testing/expect result))
    (let [is-one (k/switch number
                   :one true
                   false)]
      (try (testing/expect is-one)))))

(comment
  (switch-on-non-exhaustive-enum))
