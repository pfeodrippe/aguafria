(ns learn.examples.idiomatic-advanced.wasi-preopens
  (:require aguafria.std
            [aguafria.std.log :as log]
            [aguafria.std.process :as process]
            [aguafria.zig :as az]))

;; Target: wasm32-wasi.
(az/defn main :void
  [[init process/Init]]
  (for [[preopen ((az/field (az/field (az/field init :preopens) :map) :keys))]
        [index (az/op ".." 0)]]
    (log/info "{d}: {s}" [index preopen])))
