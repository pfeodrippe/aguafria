(ns learn.example.runtime-unwrap-null
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void []
  (let [optional-number (k/var nil [:optional :i32])]
    (k/= :_ (k/& optional-number))
    (let [number (a/unwrap optional-number)]
      (debug/print "value: {}\n" [number]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
