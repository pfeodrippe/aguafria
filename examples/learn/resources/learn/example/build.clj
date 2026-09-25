(ns learn.example.build
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.Build :as build]
            [aguafria.std.Build.Step.Compile :as compile-step]
            [aguafria.zig :as az]))

(az/defn build :void
  [[builder [:* std/Build]]]
  (let [optimize ((az/field builder :standardOptimizeOption) {})
        executable ((az/field builder :addExecutable)
                    {:name "example"
                     :root_module ((az/field builder :createModule)
                                   {:root_source_file ((az/field builder :path) "example.zig")
                                    :optimize optimize})})]
    ((az/field (build/-default_step builder) :dependOn)
     (k/& (compile-step/-step executable)))))
