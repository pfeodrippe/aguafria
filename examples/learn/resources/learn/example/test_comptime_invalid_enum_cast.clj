(ns learn.example.test-comptime-invalid-enum-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defenum Foo
  [:a
   :b
   :c])

(az/defcomptime reject-invalid-tag
  (let [tag-value (ak/u2 3)
        value (ak/as (ak/enumFromInt tag-value) Foo)]
    (set! _ value)))
