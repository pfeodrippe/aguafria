(ns learn.example.build-c
  (:require [aguafria.keyword :as k]
            [aguafria.std :as std]
            [aguafria.std.Build :as build]
            [aguafria.std.Build.Step.Compile :as compile-step]
            [aguafria.std.Build.Step.Run :as run-step]
            [aguafria.zig :as az]))

(az/defn build :void
  [[builder [:* std/Build]]]
  (let [library ((:addLibrary builder)
                 {:linkage :.dynamic
                  :name "mathtest"
                  :root_module ((:createModule builder)
                                {:root_source_file ((:path builder) "mathtest.zig")})
                  :version {:major 1 :minor 0 :patch 0}})
        executable ((:addExecutable builder)
                    {:name "test"
                     :root_module ((:createModule builder)
                                   {:link_libc true})})]
    ((:addCSourceFile (compile-step/-root_module executable))
     {:file ((:path builder) "test.c")
      :flags (k/& ["-std=c99"])})
    ((:linkLibrary (compile-step/-root_module executable)) library)
    ((:dependOn (build/-default_step builder))
     (k/& (compile-step/-step executable)))

    (let [run-command ((:run executable))
          test-step ((:step builder) "test" "Test the program")]
      ((:dependOn test-step) (k/& (run-step/-step run-command))))))
