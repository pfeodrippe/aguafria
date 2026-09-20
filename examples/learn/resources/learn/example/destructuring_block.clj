(ns learn.example.destructuring-block
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [digits (az/array-init [3 8 9 0 7 4 1] [:array :_ :i8])
        [minimum maximum]
        (let [smallest (ak/var 127 :i8)
              largest (ak/var -128 :i8)]
          (for [digit digits]
            (when (< digit smallest)
              (ak/= smallest digit))
            (when (> digit largest)
              (ak/= largest digit)))
          [smallest largest])]
    (debug/print "min = {}\n" [minimum])
    (debug/print "max = {}\n" [maximum])))

(comment
  (main))
