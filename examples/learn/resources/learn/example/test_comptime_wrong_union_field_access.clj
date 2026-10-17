(ns learn.example.test-comptime-wrong-union-field-access
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defunion Foo
  [[:float :f32]
   [:int :u32]])

(a/defcomptime reject-inactive-field
  (let [f (k/var (Foo {:int 42}))]
    (= (:float f) 12.34)))
