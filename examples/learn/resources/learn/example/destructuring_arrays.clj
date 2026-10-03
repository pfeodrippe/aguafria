(ns learn.example.destructuring-arrays
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn- swizzle-rgba-to-bgra [:array 4 :u8]
  [[rgba [:array 4 :u8]]]
  ;; readable swizzling by destructuring
  (let [[r g b a] rgba]
    [b g r a]))

(a/defn main :void
  []
  (let [pos (a/array [1 2] :i32)
        [x y] pos
        orange (a/array [255 165 0 255] :u8)]
    (debug/print "x = {}, y = {}\n" [x y])
    (debug/print "{any}\n" [(swizzle-rgba-to-bgra orange)])))

(comment
  (main))
