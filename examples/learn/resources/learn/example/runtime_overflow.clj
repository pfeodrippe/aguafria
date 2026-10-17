(ns learn.example.runtime-overflow
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void []
  (let [byte (k/var 255 :u8)]
    (+= byte 1)
    (debug/print "value: {}\n" [byte])))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
