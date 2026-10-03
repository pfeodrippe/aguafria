(ns learn.example.enum-export
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defenum Foo
  {:type :c_int}
  [:a
   :b
   :c])

(a/defn entry :void
  {:attrs #{k/export}}
  [[foo Foo]]
  (k/= :_ foo))

(comment
  (entry :a))
