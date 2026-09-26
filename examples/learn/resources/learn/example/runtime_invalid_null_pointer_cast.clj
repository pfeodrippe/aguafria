(ns learn.example.runtime-invalid-null-pointer-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [opt-ptr (k/var nil [:optional [:* :i32]])]
    (k/= :_ (k/& opt-ptr))
    (let [ptr (k/as (k/ptrCast opt-ptr) [:* :i32])]
      (k/= :_ ptr))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
