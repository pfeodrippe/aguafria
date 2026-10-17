(ns learn.example.libc-export-entry-point
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [clojure.core :as c]))

(a/defn main :c_int
  {:attrs #{export}}
  [[argc :c_int] [argv [:many-const [:sentinel-const :u8 0]]]]
  (let [args (a/slice argv 0 (intCast argc))]
    (debug/print "Hello! argv[0] is '{s}'\n" [(a/get args 0)])
    0))

(comment
  (c/with-open [arena (java.lang.foreign.Arena/ofConfined)]
    (let [argv0 (.allocateFrom arena "hello")
          argv (.allocate arena java.lang.foreign.ValueLayout/ADDRESS 1)]
      (.setAtIndex argv java.lang.foreign.ValueLayout/ADDRESS 0 argv0)
      (main 1 argv))))
