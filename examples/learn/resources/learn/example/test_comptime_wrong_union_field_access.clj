(ns learn.example.test-comptime-wrong-union-field-access
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/union
    [[:float :f32]
     [:int :u32]]))

(az/defcomptime reject-inactive-field
  (let [value (ak/var (az/init {:int 42} Foo))]
    (ak/= (az/field value :float) 12.34)))
