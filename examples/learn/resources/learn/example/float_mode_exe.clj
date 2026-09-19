(ns learn.example.float-mode-exe
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defextern foo_strict
  {:zig/prefix "extern"}
  :- :f64
  [[x :f64]])

(az/defextern foo_optimized
  {:zig/prefix "extern"}
  :- :f64
  [[x :f64]])

(az/defn main :void
  []
  (let [x 0.001]
    (debug/print "optimized = {}\n" [(foo_optimized x)])
    (debug/print "strict = {}\n" [(foo_strict x)])))
