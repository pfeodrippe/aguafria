(ns learn.example.runtime-remainder-division-by-zero
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defn main :void []
  (let [a (k/var 10 :u32)
        b (k/var 0 :u32)]
    (= :_ [(k/& a) (k/& b)])
    (let [c (% a b)]
      (debug/print "value: {}\n" [c]))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
