(ns pitoco-plugin.build
  (:require [aguafria.zig :as az]
            [aguafria.zig.build :as build]
            [clojure.java.io :as io]))

(defn -main [& _]
  (build/load-source-only! 'pitoco-plugin.rewind)
  (io/make-parents "build/plugin")
  (prn (select-keys
        (az/build! 'pitoco-plugin.rewind
                   {:kind :dynamic-lib :name "pitoco-rewind"
                    :output (io/file "build" (System/mapLibraryName "pitoco-rewind"))
                    :optimize "ReleaseSafe" :reloadable? false :async? false})
        [:output-path]))
  (shutdown-agents))
