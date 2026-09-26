(ns learn.example.test-comptime-wrong-union-field-access
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/union
   [[:float :f32]
    [:int :u32]]))

(az/defcomptime reject-inactive-field
  (let [f (k/var (Foo {:int 42}))]
    (k/= (:float f) 12.34)))
