(ns learn.example.runtime-invalid-null-pointer-cast
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn main :void []
  (let [opt-ptr (k/var nil [:optional [:* :i32]])]
    (= :_ (k/& opt-ptr))
    (let [ptr (as (ptrCast opt-ptr) [:* :i32])]
      (= :_ ptr))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
