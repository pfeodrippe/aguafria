(ns learn.example.zero-bit-types
  (:require [aguafria.zig :as az]))

(az/defn entry :void {:attrs #{:export}} []
  (let [^{:var :void} first-value (az/block)
        ^{:var :void} second-value (az/block)]
    (set! first-value second-value)
    (set! second-value first-value)))

(comment
  (entry))
