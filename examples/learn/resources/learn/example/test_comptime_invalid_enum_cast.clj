(ns learn.example.test-comptime-invalid-enum-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defenum Foo {:type :u2}
  [:a
   :b
   :c])

(a/defcomptime reject-invalid-tag
  (let [a (k/u2 3)
        b (k/as (k/fromBackingInt a) Foo)]
    (k/= :_ b)))
