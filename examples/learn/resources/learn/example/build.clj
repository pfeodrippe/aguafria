(ns learn.example.build
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.Build :as build]
            [aguafria.std.Build.Step.Compile :as compile-step]
            [aguafria.zig :as az]))

(az/defn build :void
  [[builder [:* std/Build]]]
  (let [optimize ((:standardOptimizeOption builder) {})
        executable ((:addExecutable builder)
                    {:name "example"
                     :root_module ((:createModule builder)
                                   {:root_source_file ((:path builder) "example.zig")
                                    :optimize optimize})})]
    ((:dependOn (build/-default_step builder))
     (k/& (compile-step/-step executable)))))
