(ns learn.example.build
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std :as std]
            [aguafria.std.Build :as build]
            [aguafria.std.Build.Step.Compile :as compile-step]
            [aguafria.zig :as a]))

(a/defn build :void
  [[b [:* std/Build]]]
  (let [optimize ((:standardOptimizeOption b) {})
        exe ((:addExecutable b)
             {:name "example"
              :root_module ((:createModule b)
                            {:root_source_file ((:path b) "example.zig")
                             :optimize optimize})})]
    ((:dependOn (build/-default_step b))
     (k/& (compile-step/-step exe)))))
