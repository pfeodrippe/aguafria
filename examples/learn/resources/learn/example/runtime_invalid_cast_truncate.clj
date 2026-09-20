(ns learn.example.runtime-invalid-cast-truncate
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [spartan-count (ak/var 300 :u16)]
    (ak/= :_ (ak/& spartan-count))
    (let [byte (ak/u8 (ak/intCast spartan-count))]
      (debug/print "value: {}\n" [byte]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
