(ns learn.example.test-no-op-casts
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn- foo :void
  [[_ [:*const :i32]]])

(a/deftest type-coercion-const-qualification
  (let [a (k/var 1 :i32)
        b (as (k/& a) [:* :i32])]
    (foo b)))

(comment
  (type-coercion-const-qualification))
