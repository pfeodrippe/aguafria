(ns learn.example.runtime-invalid-null-pointer-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn main :void []
  (let [optional-pointer (ak/var nil [:optional [:* :i32]])]
    (ak/= :_ (& optional-pointer))
    ;; The same invalid conversion is diagnosed by a runtime safety check.
    (let [pointer (ak/as (ak/ptrCast optional-pointer) [:* :i32])]
      (ak/= :_ pointer))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
