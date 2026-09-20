(ns learn.example.wasi-preopens
  (:require [aguafria.std.log :as log]
            [aguafria.std.process :as process]
            [aguafria.std.process.Init :as process-init]
            [aguafria.std.process.Preopens :as preopens]
            [aguafria.zig :as az]))

;; Target: wasm32-wasi.
(az/defn main :void
  [[init process/Init]]
  (for [[preopen ((az/field (-> init process-init/-preopens preopens/-map) :keys))]
        [index (az/op ".." 0)]]
    (log/info "{d}: {s}" [index preopen])))

(comment
  (main))
