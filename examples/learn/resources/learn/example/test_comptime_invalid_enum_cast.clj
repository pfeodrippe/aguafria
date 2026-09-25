(ns learn.example.test-comptime-invalid-enum-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defenum Foo
  [:a
   :b
   :c])

(az/defcomptime reject-invalid-tag
  (let [tag-value (k/u2 3)
        value (k/as (k/enumFromInt tag-value) Foo)]
    (k/= :_ value)))
