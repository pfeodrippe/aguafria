(ns learn.example.destructuring-block
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (let [digits (az/array-init [:array _ :i8] [3 8 9 0 7 4 1])
        [minimum maximum]
        (let [^{:var :i8} smallest 127
              ^{:var :i8} largest -128]
          (for [digit digits]
            (when (< digit smallest)
              (set! smallest digit))
            (when (> digit largest)
              (set! largest digit)))
          [smallest largest])]
    (debug/print "min = {}\n" [minimum])
    (debug/print "max = {}\n" [maximum])))

(comment
  (main))
