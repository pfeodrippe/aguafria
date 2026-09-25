(ns learn.example.build-object
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.Build.Step.Compile :as compile-step]
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
    ((az/field (compile-step/-root_module executable) :addCSourceFile)
     {:file ((az/field builder :path) "test.c")
      :flags (k/& ["-std=c99"])})
    ((az/field (compile-step/-root_module executable) :addObject) object)
    ((az/field builder :installArtifact) executable)))
