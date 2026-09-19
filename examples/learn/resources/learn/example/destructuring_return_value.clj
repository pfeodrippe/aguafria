(ns learn.example.destructuring-return-value
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Division
  (az/container {:kind :struct}
    (az/tuple-field-decl :u32)
    (az/tuple-field-decl :u32)))

(az/defn- divmod Division
  [[numerator :u32] [denominator :u32]]
  [(/ numerator denominator) (ak/% numerator denominator)])

(az/defn main :void
  []
  (let [[quotient remainder] (divmod 10 3)]
    (debug/print "10 / 3 = {}\n" [quotient])
    (debug/print "10 % 3 = {}\n" [remainder])))
