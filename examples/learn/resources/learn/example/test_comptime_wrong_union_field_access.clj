(ns learn.example.test-comptime-wrong-union-field-access
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defunion Foo
  [[:float :f32]
   [:int :u32]])

(a/defcomptime reject-inactive-field
  (let [f (k/var (Foo {:int 42}))]
    (k/= (:float f) 12.34)))
