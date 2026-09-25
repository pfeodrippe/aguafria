(ns learn.example.destructuring-block
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [digits (az/array-init [3 8 9 0 7 4 1] [:array :_ :i8])
        [minimum maximum]
        (let [smallest (k/var 127 :i8)
              largest (k/var -128 :i8)]
          (k/for [digit digits]
            (when (k/< digit smallest)
              (k/= smallest digit))
            (when (k/> digit largest)
              (k/= largest digit)))
          [smallest largest])]
    (debug/print "min = {}\n" [minimum])
    (debug/print "max = {}\n" [maximum])))

(comment
  (main))
