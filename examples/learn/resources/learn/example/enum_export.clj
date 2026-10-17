(ns learn.example.enum-export
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defenum Foo
  {:type :c_int}
  [:a
   :b
   :c])

(a/defn entry :void
  {:attrs #{export}}
  [[foo Foo]]
  (= :_ foo))

(comment
  (entry :a))
