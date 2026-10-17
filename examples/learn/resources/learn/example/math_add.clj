(ns learn.example.math-add
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.std.math :as math]
            [aguafria.zig :as a]))

(a/defn main :!void
  []
  (let [byte (k/var 255 :u8)]
    (= byte
       (a/if-capture {:payload [result] :error [err]} (math/add :u8 byte 1)
                     result
                     (a/block
                      (debug/print "unable to add one: {s}\n" [(errorName err)])
                      (return err))))
    (debug/print "result: {}\n" [byte])))

(comment
  (main))
