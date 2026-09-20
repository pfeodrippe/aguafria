(ns learn.example.enum-export
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defenum Foo
  {:argument :c_int}
  [:a
   :b
   :c])

(az/defn entry :void
  {:attrs #{:export}}
  [[foo Foo]]
  (ak/= :_ foo))

(comment
  (entry :a))
