(ns learn.example.test-comptime-invalid-null-pointer-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defcomptime reject-null-pointer
  (let [opt-ptr (k/as nil [:optional [:* :i32]])
        ptr (k/as (k/ptrCast opt-ptr) [:* :i32])]
    (k/= :_ ptr)))
