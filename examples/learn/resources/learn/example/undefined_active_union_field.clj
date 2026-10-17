(ns learn.example.undefined-active-union-field
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defunion Foo
  [[:float :f32]
   [:int :u32]])

(a/defn- bar :void [[f [:* Foo]]]
  (= (:float f) 12.34))

(a/defn main :void []
  (let [f (k/var (Foo {:int 42}))]
    (= f (Foo {:float undefined}))
    (bar (k/& f))
    (debug/print "value: {}\n" [(:float f)])))

(comment
  (main))
