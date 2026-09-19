(ns learn.curated.hello-again
  "Converted from hello_again.zig"
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void
  []
  (debug/print "Hello, {s}!\n" ["World"]))
