(ns learn.example.runtime-out-of-bounds-float-to-integer-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [float-value (k/var 4294967296 :f32)]
    (k/= :_ (k/& float-value))
    (let [integer-value (k/i32 (k/intFromFloat float-value))]
      (k/= :_ integer-value))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
