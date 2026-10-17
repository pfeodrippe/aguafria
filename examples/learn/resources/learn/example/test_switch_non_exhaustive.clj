(ns learn.example.test-switch-non-exhaustive
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]
            [clojure.core :as c]))

(c/ns-unmap c/*ns* 'Number)

(a/defenum Number
  {:type :u8}
  [:one
   :two
   :three
   :_])

(a/deftest switch-on-non-exhaustive-enum
  (let [number (:one Number)
        result (switch number
                 :one true
                 (:two :three) false
                 _ false)]
    (k/try (testing/expect result))
    (let [is-one (switch number
                   :one true
                   false)]
      (k/try (testing/expect is-one)))))

(comment
  (switch-on-non-exhaustive-enum))
