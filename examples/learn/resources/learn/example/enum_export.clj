(ns learn.example.enum-export
  (:require [aguafria.zig :as az]))

(az/defenum Foo
  {:argument :c_int}
  [:a
   :b
   :c])

(az/defn entry :void
  {:attrs #{:export}}
  [[foo Foo]]
  (set! _ foo))

(comment
  (entry :a))
