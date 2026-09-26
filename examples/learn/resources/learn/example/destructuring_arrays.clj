(ns learn.example.destructuring-arrays
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- swizzle-rgba-to-bgra [:array 4 :u8]
  [[rgba [:array 4 :u8]]]
  ;; Name the channels instead of repeating numeric indices.
  (let [[red green blue alpha] rgba]
    [blue green red alpha]))

(az/defn main :void
  []
  (let [position (az/array [1 2] :i32)
        [x y] position
        orange (az/array [255 165 0 255] :u8)]
    (debug/print "x = {}, y = {}\n" [x y])
    (debug/print "{any}\n" [(swizzle-rgba-to-bgra orange)])))

(comment
  (main))
