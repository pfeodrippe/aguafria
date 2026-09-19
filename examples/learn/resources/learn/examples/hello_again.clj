(ns learn.curated.hello-again
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (debug/print "Hello, {s}!\n" ["World"]))
