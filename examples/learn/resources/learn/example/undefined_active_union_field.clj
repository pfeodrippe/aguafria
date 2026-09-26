(ns learn.example.undefined-active-union-field
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/union
   [[:float :f32]
    [:int :u32]]))

(az/defn- bar :void [[f [:* Foo]]]
  (k/= (:float f) 12.34))

(az/defn main :void []
  (let [f (k/var (Foo {:int 42}))]
    (k/= f (Foo {:float k/undefined}))
    (bar (k/& f))
    (debug/print "value: {}\n" [(:float f)])))

(comment
  (main))
