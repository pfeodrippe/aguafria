(ns learn.example.undefined-active-union-field
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/union
    [[:float :f32]
     [:int :u32]]))

(az/defn- bar :void [[f [:* Foo]]]
  (ak/= (az/field f :float) 12.34))

(az/defn main :void []
  (let [f (ak/var (Foo {:int 42}))]
    ;; Select the float field first; its payload can be initialized separately.
    (ak/= f (Foo {:float ak/undefined}))
    (bar (ak/& f))
    (debug/print "value: {}\n" [(az/field f :float)])))

(comment
  (main))
