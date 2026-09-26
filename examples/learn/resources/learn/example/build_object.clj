(ns learn.example.build-object
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.Build.Step.Compile :as compile-step]
            [aguafria.zig :as az]))

(az/defn build :void
  [[builder [:* std/Build]]]
  (let [object ((:addObject builder)
                {:name "base64"
                 :root_module ((:createModule builder)
                               {:root_source_file ((:path builder) "base64.zig")})})
        executable ((:addExecutable builder)
                    {:name "test"
                     :root_module ((:createModule builder)
                                   {:link_libc true})})]
    ((:addCSourceFile (compile-step/-root_module executable))
     {:file ((:path builder) "test.c")
      :flags (k/& ["-std=c99"])})
    ((:addObject (compile-step/-root_module executable)) object)
    ((:installArtifact builder) executable)))
