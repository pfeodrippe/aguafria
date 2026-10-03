(ns learn.example.enum-export-error
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defenum Foo
  [:a
   :b
   :c])

(a/defn entry :void
  {:attrs #{k/export}}
  [[foo Foo]]
  (k/= :_ foo))

(comment
  (entry :a))
