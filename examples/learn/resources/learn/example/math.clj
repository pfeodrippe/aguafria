(ns learn.example.math
  (:refer-clojure :exclude [print])
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defextern print :void
  [[value :i32]])

(a/defn add :void
  {:attrs #{k/export}}
  [[a :i32] [b :i32]]
  (print (k/+ a b)))

(comment
  ;; This example runs in a WebAssembly host, not through the native JVM bridge.
  ;; Same options as: zig build-exe math.zig -target wasm32-freestanding
  ;;                  -fno-entry --export=add
  (require '[clojure.java.io :as io]
           '[clojure.java.shell :as sh])
  (let [wasm (a/build! 'learn.example.math
                       {:kind :exe
                        :target "wasm32-freestanding"
                        :output "build/math.wasm"
                        :zig-args ["-fno-entry" "--export=add"]})
        result (sh/sh "node" "-e"
                      (slurp (io/resource "learn/host/math.cjs"))
                      (:output-path wasm))]
    (when-not (zero? (:exit result))
      (throw (ex-info "WebAssembly Node host failed" result)))
    (clojure.core/print (:out result))))
