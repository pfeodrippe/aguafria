(ns learn.example.runtime-out-of-bounds-float-to-integer-cast
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn main :void []
  (let [float (k/var 4294967296 :f32)] ; runtime-known
    (= :_ (k/& float))
    (let [int (i32 (intFromFloat float))]
      (= :_ int))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
