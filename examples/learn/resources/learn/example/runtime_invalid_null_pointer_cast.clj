(ns learn.example.runtime-invalid-null-pointer-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [optional-pointer (k/var nil [:optional [:* :i32]])]
    (k/= :_ (k/& optional-pointer))
    ;; The same invalid conversion is diagnosed by a runtime safety check.
    (let [pointer (k/as (k/ptrCast optional-pointer) [:* :i32])]
      (k/= :_ pointer))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
