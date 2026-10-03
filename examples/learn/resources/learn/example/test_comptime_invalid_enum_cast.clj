(ns learn.example.test-comptime-invalid-enum-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defenum Foo {:type :u2}
  [:a
   :b
   :c])

(az/defcomptime reject-invalid-tag
  (let [a (k/u2 3)
        b (k/as (k/fromBackingInt a) Foo)]
    (k/= :_ b)))
