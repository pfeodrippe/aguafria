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

(comment
  (require 'learn.example.float-mode-obj)
  (let [object (az/build! 'learn.example.float-mode-obj
                          {:kind :object
                           :optimize "ReleaseFast"
                           :zig-args ["-fPIC"]})
        config (az/configuration)]
    (try
      (az/configure!
       {:zig-args-by-module
        (assoc (:zig-args-by-module config)
               "learn.example.float-mode-exe" [(:output-path object)])})
      (main)
      (finally
        (az/configure! config)))))
