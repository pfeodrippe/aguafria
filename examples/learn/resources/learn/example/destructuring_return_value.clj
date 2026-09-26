(ns learn.example.destructuring-return-value
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- divmod (az/struct [(az/tuple-field-decl :u32)
                             (az/tuple-field-decl :u32)])
  [[numerator :u32] [denominator :u32]]
  [(k// numerator denominator) (k/% numerator denominator)])

(az/defn main :void
  []
  (let [[div mod] (divmod 10 3)]
    (debug/print "10 / 3 = {}\n" [div])
    (debug/print "10 % 3 = {}\n" [mod])))

(comment
  (main))
