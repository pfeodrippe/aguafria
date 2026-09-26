(ns learn.example.math-add
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]))

(az/defn main :!void
  []
  (let [byte (k/var 255 :u8)]
    (k/= byte
         (az/if-capture {:payload [result] :error [err]} (math/add :u8 byte 1)
                        result
                        (az/block
                         (debug/print "unable to add one: {s}\n" [(k/errorName err)])
                         (k/return err))))
    (debug/print "result: {}\n" [byte])))

(comment
  (main))
