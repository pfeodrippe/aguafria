(ns learn.example.destructuring-return-value
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn- divmod (a/struct [(a/tuple-field-decl :u32)
                           (a/tuple-field-decl :u32)])
  [[numerator :u32] [denominator :u32]]
  [(/ numerator denominator) (% numerator denominator)])

(a/defn main :void
  []
  (let [[div mod] (divmod 10 3)]
    (debug/print "10 / 3 = {}\n" [div])
    (debug/print "10 % 3 = {}\n" [mod])))

(comment
  (main))
