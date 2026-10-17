(ns learn.example.float-mode-exe
  (:refer-clojure :only [])
  (:require [aguafria.std.debug :as debug]
            [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]
            [clojure.core :as c]))

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
  (c/require 'learn.example.float-mode-obj)
  (let [object (a/build! 'learn.example.float-mode-obj
                         {:kind :object
                          :optimize "fast"
                          :zig-args ["-fPIC"]})
        config (a/configuration)]
    (try
      (a/configure!
       {:zig-args-by-module
        (c/assoc (:zig-args-by-module config)
                 "learn.example.float-mode-exe" [(:output-path object)])})
      (main)
      (finally
        (a/configure! config)))))
