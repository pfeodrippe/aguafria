(ns learn.example.test-comptime-invalid-enum-cast
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defenum Foo {:type :u2}
  [:a
   :b
   :c])

(a/defcomptime reject-invalid-tag
  (let [a (u2 3)
        b (as (fromBackingInt a) Foo)]
    (= :_ b)))
