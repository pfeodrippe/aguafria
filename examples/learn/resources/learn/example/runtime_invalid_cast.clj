(ns learn.example.runtime-invalid-cast
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [signed-value (k/var -1 :i32)]
    (k/= :_ (k/& signed-value))
    (let [unsigned-value (k/u32 (k/intCast signed-value))]
      (debug/print "value: {}\n" [unsigned-value]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
