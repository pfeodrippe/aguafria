(ns learn.example.wasi-preopens
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.log :as log]
            [aguafria.std.process :as process]
            [aguafria.std.process.Init :as process-init]
            [aguafria.std.process.Preopens :as preopens]
            [aguafria.zig :as a]
            [clojure.core :as c]))

;; Target: wasm32-wasi.
(a/defn main :void
  [[init process/Init]]
  (for [preopen ((:keys (-> init process-init/-preopens preopens/-map)))
        i (a/range 0)]
    (log/info "{d}: {s}" [i preopen])))

(comment
  ;; Explicitly preopen the current directory, like wasmtime --dir=.
  (c/require '[clojure.java.io :as io]
             '[clojure.java.shell :as sh])
  (let [wasm (a/build! 'learn.example.wasi-preopens
                       {:kind :exe :target "wasm32-wasi"
                        :output "build/wasi_preopens.wasm"})
        result (sh/sh "node" "--no-warnings" "-e"
                      (c/slurp (io/resource "learn/host/wasi.cjs"))
                      "--" "--dir=." (:output-path wasm))]
    (when-not (c/zero? (:exit result))
      (throw (c/ex-info "WASI host failed" result)))
    (clojure.core/print (c/str (:out result) (:err result)))))
