(ns learn.example.wasi-args
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.std.process :as process]
            [aguafria.std.process.Init :as process-init]
            [aguafria.std.process.Init.Minimal :as minimal-init]
            [aguafria.zig :as a]
            [clojure.core :as c]))

;; Target: wasm32-wasi.
(a/defn main :!void
  [[init process/Init]]
  (let [args (k/try ((:toSlice (-> init process-init/-minimal minimal-init/-args))
                     ((:allocator (process-init/-arena init)))))]
    (for [i (a/range 0) arg args]
      (debug/print "{d}: {s}\n" [i arg]))))

(comment
  ;; WASI supplies std.process.Init; do not call main without it in the JVM.
  (c/require '[clojure.java.io :as io]
             '[clojure.java.shell :as sh])
  (let [wasm (a/build! 'learn.example.wasi-args
                       {:kind :exe :target "wasm32-wasi"
                        :output "build/wasi_args.wasm"})
        result (sh/sh "node" "--no-warnings" "-e"
                      (c/slurp (io/resource "learn/host/wasi.cjs"))
                      (:output-path wasm) "123" "hello")]
    (when-not (c/zero? (:exit result))
      (throw (c/ex-info "WASI host failed" result)))
    (clojure.core/print (c/str (:out result) (:err result)))))
