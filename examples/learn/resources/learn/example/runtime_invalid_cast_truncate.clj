(ns learn.example.runtime-invalid-cast-truncate
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^{:var :u16} spartan-count 300]
    (set! _ (ak/& spartan-count))
    (let [^{:zig/type :u8} byte (ak/intCast spartan-count)]
      (debug/print "value: {}\n" [byte]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
