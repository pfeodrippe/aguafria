(ns learn.example.runtime-unwrap-null
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^{:var [:optional :i32]} optional-number nil]
    (set! _ (ak/& optional-number))
    (let [number (az/unwrap optional-number)]
      (debug/print "value: {}\n" [number]))))
