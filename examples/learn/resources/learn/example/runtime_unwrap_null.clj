(ns learn.example.runtime-unwrap-null
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [optional-number (ak/var nil [:optional :i32])]
    (ak/= :_ (ak/& optional-number))
    (let [number (az/unwrap optional-number)]
      (debug/print "value: {}\n" [number]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
