(ns learn.example.struct-default-field-values
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Foo
  [[:a {:default 1234} :i32]
   [:b :i32]])

(a/deftest default-struct-initialization-fields
  (let [x (Foo {:b 5})]
    (when (!= (+ (:a x) (:b x)) 1239)
      (comptime (unreachable)))))

(comment
  (default-struct-initialization-fields))
