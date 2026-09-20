(ns learn.example.build-c
  (:require [aguafria.std :as std]
            [aguafria.std.Build :as build]
            [aguafria.std.Build.Step.Compile :as compile-step]
            [aguafria.std.Build.Step.Run :as run-step]
            [aguafria.zig :as az]))

(az/defn build :void
  [[builder [:* std/Build]]]
  (let [library ((az/field builder :addLibrary)
                 {:linkage :.dynamic
                  :name "mathtest"
                  :root_module ((az/field builder :createModule)
                                {:root_source_file ((az/field builder :path) "mathtest.zig")})
                  :version {:major 1 :minor 0 :patch 0}})
        executable ((az/field builder :addExecutable)
                    {:name "test"
                     :root_module ((az/field builder :createModule)
                                   {:link_libc true})})]
    ((az/field (compile-step/-root_module executable) :addCSourceFile)
     {:file ((az/field builder :path) "test.c")
      :flags (& ["-std=c99"])})
    ((az/field (compile-step/-root_module executable) :linkLibrary) library)
    ((az/field (build/-default_step builder) :dependOn)
     (& (compile-step/-step executable)))

    (let [run-command ((az/field executable :run))
          test-step ((az/field builder :step) "test" "Test the program")]
      ((az/field test-step :dependOn) (& (run-step/-step run-command))))))
