(ns learn.example.test-comptime-invalid-null-pointer-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defcomptime reject-null-pointer
  (let [optional-pointer (ak/as nil [:optional [:* :i32]])
        ;; Intentionally invalid: a nonoptional pointer cannot hold null.
        pointer (ak/as (ak/ptrCast optional-pointer) [:* :i32])]
    (set! _ pointer)))
