(ns learn.example.math
  (:refer-clojure :exclude [print])
  (:require [aguafria.zig :as az]))

(az/defextern print
  {:zig/prefix "extern"}
  :- :void
  [[value :i32]])

(az/defn add :void
  {:attrs #{:export}}
  [[a :i32] [b :i32]]
  (print (+ a b)))

(comment
  (add 12 34))
