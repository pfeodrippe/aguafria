(ns learn.example.wasi-args
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.process :as process]
            [aguafria.std.process.Init :as process-init]
            [aguafria.std.process.Init.Minimal :as minimal-init]
            [aguafria.zig :as a]))

;; Target: wasm32-wasi.
(a/defn main :!void
  [[init process/Init]]
  (let [args (try ((:toSlice (-> init process-init/-minimal minimal-init/-args))
                   ((:allocator (process-init/-arena init)))))]
    (k/for [i (a/range 0) arg args]
      (debug/print "{d}: {s}\n" [i arg]))))

(comment
  (main))
