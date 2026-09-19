(ns learn.example.libc-export-entry-point
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :c_int
  {:attrs #{:export}}
  [[argc :c_int] [argv [:many-const [:sentinel-const :u8 0]]]]
  (let [args (az/slice argv 0 (ak/intCast argc))]
    (debug/print "Hello! argv[0] is '{s}'\n" [(az/index args 0)])
    0))
