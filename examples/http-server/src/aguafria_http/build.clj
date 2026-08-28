(ns aguafria-http.build
  "Build the same server graph as one JVM-free executable."
  (:require [aguafria.zig :as az]
            [aguafria.zig.build :as zig-build]
            [clojure.java.io :as io]))

(defn -main
  [& _]
  (zig-build/load-source-only! 'aguafria-http.server)
  (let [output (io/file "build/http-server")]
    (io/make-parents output)
    (prn
     (az/build!
      'aguafria-http.server
      {:kind :exe
       :name "aguafria-http-server"
       :output output
       :optimize "ReleaseFast"
       :reloadable? false
       :async? false})))
  (shutdown-agents))
