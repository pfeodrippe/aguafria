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
  (main))
