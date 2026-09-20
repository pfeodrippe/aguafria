(ns learn.example.runtime-out-of-bounds-float-to-integer-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [^:var float-value (ak/f32 4294967296)]
    (set! _ (ak/& float-value))
    (let [integer-value (ak/i32 (ak/intFromFloat float-value))]
      (set! _ integer-value))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
