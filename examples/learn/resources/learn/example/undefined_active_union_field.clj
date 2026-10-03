(ns learn.example.undefined-active-union-field
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defunion Foo
  [[:float :f32]
   [:int :u32]])

(a/defn- bar :void [[f [:* Foo]]]
  (k/= (:float f) 12.34))

(a/defn main :void []
  (let [f (k/var (Foo {:int 42}))]
    (k/= f (Foo {:float k/undefined}))
    (bar (k/& f))
    (debug/print "value: {}\n" [(:float f)])))

(comment
  (main))
