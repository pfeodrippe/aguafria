(ns learn.examples.idiomatic-interop.build
  "Converted from build.zig"
  (:require [aguafria.std :as std]
            [aguafria.zig :as az]))

(az/defn build :void
  [[builder [:* std/Build]]]
  (let [optimize ((az/field builder :standardOptimizeOption) {})
        executable ((az/field builder :addExecutable)
                     {:name "example"
                      :root_module ((az/field builder :createModule)
                                    {:root_source_file ((az/field builder :path) "example.zig")
                                     :optimize optimize})})]
    ((az/field (az/field builder :default_step) :dependOn)
     (& (az/field executable :step)))))
