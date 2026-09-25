(ns learn.example.wasi-args
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.process :as process]
            [aguafria.std.process.Init :as process-init]
            [aguafria.std.process.Init.Minimal :as minimal-init]
            [aguafria.zig :as az]))

;; Target: wasm32-wasi.
(az/defn main :!void
  [[init process/Init]]
  (let [args (try ((az/field (-> init process-init/-minimal minimal-init/-args) :toSlice)
                   ((az/field (process-init/-arena init) :allocator))))]
    (k/for [[index (az/op ".." 0)] [argument args]]
      (debug/print "{d}: {s}\n" [index argument]))))

(comment
  (main))
