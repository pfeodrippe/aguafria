(ns learn.example.runtime-invalid-cast
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void []
  (let [value (k/var -1 :i32)] ; runtime-known
    (= :_ (k/& value))
    (let [unsigned (u32 (intCast value))]
      (debug/print "value: {}\n" [unsigned]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
