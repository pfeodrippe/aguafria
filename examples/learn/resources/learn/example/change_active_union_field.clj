(ns learn.example.change-active-union-field
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/union
    [[:float :f32]
     [:int :u32]]))

(az/defn- bar :void [[f [:* Foo]]]
  (k/= @f (Foo {:float 12.34}))
  (debug/print "value: {}\n" [(az/field f :float)]))

(az/defn main :void []
  (let [f (k/var (Foo {:int 42}))]
    (bar (k/& f))))

(comment
  (main))
