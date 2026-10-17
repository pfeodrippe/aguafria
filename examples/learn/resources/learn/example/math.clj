(ns learn.example.math
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [clojure.core :as c]))

(a/defextern print :void
  [[value :i32]])

(a/defn add :void
  {:attrs #{export}}
  [[a :i32] [b :i32]]
  (print (+ a b)))

(comment
  ;; This example runs in a WebAssembly host, not through the native JVM bridge.
  ;; Same options as: zig build-exe math.zig -target wasm32-freestanding
  ;;                  -fno-entry --export=add
  (c/require '[clojure.java.io :as io]
             '[clojure.java.shell :as sh])
  (let [wasm (a/build! 'learn.example.math
                       {:kind :exe
                        :target "wasm32-freestanding"
                        :output "build/math.wasm"
                        :zig-args ["-fno-entry" "--export=add"]})
        result (sh/sh "node" "-e"
                      (c/slurp (io/resource "learn/host/math.cjs"))
                      (:output-path wasm))]
    (when-not (c/zero? (:exit result))
      (throw (c/ex-info "WebAssembly Node host failed" result)))
    (clojure.core/print (:out result))))
