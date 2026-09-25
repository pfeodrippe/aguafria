(ns learn.example.enum-export-error
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defenum Foo
  [:a
   :b
   :c])

(az/defn entry :void
  {:attrs #{k/export}}
  [[foo Foo]]
  (k/= :_ foo))

(comment
  (entry :a))
