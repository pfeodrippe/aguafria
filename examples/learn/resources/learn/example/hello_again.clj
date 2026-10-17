(ns learn.example.hello-again
  (:refer-clojure :only [])
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defn main :void
  []
  (debug/print "Hello, {s}!\n" ["World"]))

(comment
  (main))
