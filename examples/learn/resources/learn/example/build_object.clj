(ns learn.example.build-object
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.Build.Step.Compile :as compile-step]
            [aguafria.zig :as az]))

(az/defn build :void
  [[b [:* std/Build]]]
  (let [obj ((:addObject b)
             {:name "base64"
              :root_module ((:createModule b)
                            {:root_source_file ((:path b) "base64.zig")})})
        exe ((:addExecutable b)
             {:name "test"
              :root_module ((:createModule b)
                            {:link_libc true})})]
    ((:addCSourceFile (compile-step/-root_module exe))
     {:file ((:path b) "test.c")
      :flags (k/& ["-std=c99"])})
    ((:addObject (compile-step/-root_module exe)) obj)
    ((:installArtifact b) exe)))
