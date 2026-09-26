(ns learn.example.test-failed-int-to-float-coercion
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/deftest integer-type-is-too-large-for-implicit-cast-to-float
  (let [int (k/var 123 :u25)]
    (k/= :_ (k/& int))
    (let [float (k/f32 int)]
      (k/= :_ float))))

(comment
  (integer-type-is-too-large-for-implicit-cast-to-float))
