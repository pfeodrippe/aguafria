(ns learn.example.destructuring-arrays
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn- swizzle-rgba-to-bgra [:array 4 :u8]
  [[rgba [:array 4 :u8]]]
  ;; Name the channels instead of repeating numeric indices.
  (let [[red green blue alpha] rgba]
    [blue green red alpha]))

(az/defn main :void
  []
  (let [position (az/array-init [:array _ :i32] [1 2])
        [x y] position
        ^{:zig/type [:array 4 :u8]} orange [255 165 0 255]]
    (debug/print "x = {}, y = {}\n" [x y])
    (debug/print "{any}\n" [(swizzle-rgba-to-bgra orange)])))

(comment
  (main))
