(ns learn.example.change-active-union-field
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defunion Foo
  [[:float :f32]
   [:int :u32]])

(a/defn- bar :void [[f [:* Foo]]]
  (k/= @f (Foo {:float 12.34}))
  (debug/print "value: {}\n" [(:float f)]))

(a/defn main :void []
  (let [f (k/var (Foo {:int 42}))]
    (bar (k/& f))))

(comment
  (main))
