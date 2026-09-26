(ns learn.example.runtime-out-of-bounds-float-to-integer-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [float (k/var 4294967296 :f32)] ; runtime-known
    (k/= :_ (k/& float))
    (let [int (k/i32 (k/intFromFloat float))]
      (k/= :_ int))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
