(ns learn.example.build-c
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.Build :as build]
            [aguafria.std.Build.Step.Compile :as compile-step]
            [aguafria.std.Build.Step.Run :as run-step]
            [aguafria.zig :as az]))

(az/defn build :void
  [[b [:* std/Build]]]
  (let [lib ((:addLibrary b)
             {:linkage :.dynamic
              :name "mathtest"
              :root_module ((:createModule b)
                            {:root_source_file ((:path b) "mathtest.zig")})
              :version {:major 1 :minor 0 :patch 0}})
        exe ((:addExecutable b)
             {:name "test"
              :root_module ((:createModule b)
                            {:link_libc true})})]
    ((:addCSourceFile (compile-step/-root_module exe))
     {:file ((:path b) "test.c")
      :flags (k/& ["-std=c99"])})
    ((:linkLibrary (compile-step/-root_module exe)) lib)
    ((:dependOn (build/-default_step b))
     (k/& (compile-step/-step exe)))

    (let [run-cmd ((:run exe))
          test-step ((:step b) "test" "Test the program")]
      ((:dependOn test-step) (k/& (run-step/-step run-cmd))))))
