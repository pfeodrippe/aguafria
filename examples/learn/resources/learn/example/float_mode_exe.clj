(ns learn.example.float-mode-exe
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defextern foo_strict :f64
  [[x :f64]])

(a/defextern foo_optimized :f64
  [[x :f64]])

(a/defn main :void
  []
  (let [x 0.001]
    (debug/print "optimized = {}\n" [(foo_optimized x)])
    (debug/print "strict = {}\n" [(foo_strict x)])))

(comment
  (require 'learn.example.float-mode-obj)
  (let [object (a/build! 'learn.example.float-mode-obj
                         {:kind :object
                          :optimize "fast"
                          :zig-args ["-fPIC"]})
        config (a/configuration)]
    (try
      (a/configure!
       {:zig-args-by-module
        (assoc (:zig-args-by-module config)
               "learn.example.float-mode-exe" [(:output-path object)])})
      (main)
      (finally
        (a/configure! config)))))
