(ns learn.example.runtime-unwrap-null
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^:var optional-number (ak/as nil [:optional :i32])]
    (set! _ (ak/& optional-number))
    (let [number (az/unwrap optional-number)]
      (debug/print "value: {}\n" [number]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
