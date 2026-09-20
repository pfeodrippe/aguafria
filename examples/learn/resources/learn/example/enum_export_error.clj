(ns learn.example.enum-export-error
  (:require [aguafria.zig :as az]))

(az/defenum Foo
  [:a
   :b
   :c])

(az/defn entry :void
  {:attrs #{:export}}
  [[foo Foo]]
  (set! _ foo))

(comment
  (entry :a))
