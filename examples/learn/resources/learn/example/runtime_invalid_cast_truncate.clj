(ns learn.example.runtime-invalid-cast-truncate
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [spartan-count (k/var 300 :u16)]
    (k/= :_ (k/& spartan-count))
    (let [byte (k/u8 (k/intCast spartan-count))]
      (debug/print "value: {}\n" [byte]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
