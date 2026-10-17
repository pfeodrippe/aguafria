(ns learn.example.test-comptime-invalid-null-pointer-cast
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defcomptime reject-null-pointer
  (let [opt-ptr (as nil [:optional [:* :i32]])
        ptr (as (ptrCast opt-ptr) [:* :i32])]
    (= :_ ptr)))
