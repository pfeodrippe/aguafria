(ns learn.example.runtime-invalid-cast
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [value (k/var -1 :i32)] ; runtime-known
    (k/= :_ (k/& value))
    (let [unsigned (k/u32 (k/intCast value))]
      (debug/print "value: {}\n" [unsigned]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
