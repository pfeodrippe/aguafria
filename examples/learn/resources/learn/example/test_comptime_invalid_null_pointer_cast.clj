(ns learn.example.test-comptime-invalid-null-pointer-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-null-pointer
  (let [optional-pointer (k/as nil [:optional [:* :i32]])
        ;; Intentionally invalid: a nonoptional pointer cannot hold null.
        pointer (k/as (k/ptrCast optional-pointer) [:* :i32])]
    (k/= :_ pointer)))
