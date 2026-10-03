(ns learn.example.struct-default-field-values
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Foo
  [[:a {:default 1234} :i32]
   [:b :i32]])

(a/deftest default-struct-initialization-fields
  (let [x (Foo {:b 5})]
    (when (k/!= (k/+ (:a x) (:b x)) 1239)
      (k/comptime (k/unreachable)))))

(comment
  (default-struct-initialization-fields))
