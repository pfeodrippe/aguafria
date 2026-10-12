(ns learn.example.wasi-preopens
  (:require [aguafria.keyword :as k]
            [aguafria.std.log :as log]
            [aguafria.std.process :as process]
            [aguafria.std.process.Init :as process-init]
            [aguafria.std.process.Preopens :as preopens]
            [aguafria.zig :as a]))

;; Target: wasm32-wasi.
(a/defn main :void
  [[init process/Init]]
  (k/for [preopen ((:keys (-> init process-init/-preopens preopens/-map)))
          i (a/range 0)]
    (log/info "{d}: {s}" [i preopen])))

(comment
  ;; Explicitly preopen the current directory, like wasmtime --dir=.
  (require '[clojure.java.io :as io]
           '[clojure.java.shell :as sh])
  (let [wasm (a/build! 'learn.example.wasi-preopens
                       {:kind :exe :target "wasm32-wasi"
                        :output "build/wasi_preopens.wasm"})
        result (sh/sh "node" "--no-warnings" "-e"
                      (slurp (io/resource "learn/host/wasi.cjs"))
                      "--" "--dir=." (:output-path wasm))]
    (when-not (zero? (:exit result))
      (throw (ex-info "WASI host failed" result)))
    (clojure.core/print (str (:out result) (:err result)))))
