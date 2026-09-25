(ns learn.example.test-comptime-wrong-union-field-access
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/union
    [[:float :f32]
     [:int :u32]]))

(az/defcomptime reject-inactive-field
  (let [value (k/var (Foo {:int 42}))]
    (k/= (az/field value :float) 12.34)))
