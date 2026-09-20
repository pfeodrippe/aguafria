(ns learn.example.runtime-out-of-bounds-float-to-integer-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [float-value (ak/var 4294967296 :f32)]
    (ak/= :_ (ak/& float-value))
    (let [integer-value (ak/i32 (ak/intFromFloat float-value))]
      (ak/= :_ integer-value))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
