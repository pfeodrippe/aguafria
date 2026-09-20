(ns learn.example.build-object
  (:require [aguafria.std :as std]
            [aguafria.zig :as az]))

(az/defn build :void
  [[builder [:* std/Build]]]
  (let [object ((az/field builder :addObject)
                {:name "base64"
                 :root_module ((az/field builder :createModule)
                               {:root_source_file ((az/field builder :path) "base64.zig")})})
        executable ((az/field builder :addExecutable)
                    {:name "test"
                     :root_module ((az/field builder :createModule)
                                   {:link_libc true})})]
    ((az/field (az/field executable :root_module) :addCSourceFile)
     {:file ((az/field builder :path) "test.c")
      :flags (& ["-std=c99"])})
    ((az/field (az/field executable :root_module) :addObject) object)
    ((az/field builder :installArtifact) executable)))
