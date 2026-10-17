(ns learn.example.test-failed-int-to-float-coercion
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest integer-type-is-too-large-for-implicit-cast-to-float
  (let [int (k/var 123 :u25)]
    (= :_ (k/& int))
    (let [float (f32 int)]
      (= :_ float))))

(comment
  (integer-type-is-too-large-for-implicit-cast-to-float))
