(ns learn.example.runtime-invalid-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [signed-value (ak/var -1 :i32)]
    (ak/= :_ (ak/& signed-value))
    (let [unsigned-value (ak/u32 (ak/intCast signed-value))]
      (debug/print "value: {}\n" [unsigned-value]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
