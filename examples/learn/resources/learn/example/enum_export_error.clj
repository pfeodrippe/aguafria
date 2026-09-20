(ns learn.example.enum-export-error
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defenum Foo
  [:a
   :b
   :c])

(az/defn entry :void
  {:attrs #{:export}}
  [[foo Foo]]
  (ak/= :_ foo))

(comment
  (entry :a))
