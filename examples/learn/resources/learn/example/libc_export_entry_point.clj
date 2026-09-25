(ns learn.example.libc-export-entry-point
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :c_int
  {:attrs #{ak/export}}
  [[argc :c_int] [argv [:many-const [:sentinel-const :u8 0]]]]
  (let [args (az/slice argv 0 (ak/intCast argc))]
    (debug/print "Hello! argv[0] is '{s}'\n" [(az/index args 0)])
    0))

(comment
  (with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [argv0 (.allocateFrom arena "hello")
          argv (.allocate arena java.lang.foreign.ValueLayout/ADDRESS 1)]
      (.setAtIndex argv java.lang.foreign.ValueLayout/ADDRESS 0 argv0)
      (main 1 argv))))
