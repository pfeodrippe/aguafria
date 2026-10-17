(ns learn.example.runtime-wrong-union-field-access
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defunion Foo
  [[:float :f32]
   [:int :u32]])

(a/defn- bar :void [[f [:* Foo]]]
  (= (:float f) 12.34)
  (debug/print "value: {}\n" [(:float f)]))

(a/defn main :void []
  (let [f (k/var (Foo {:int 42}))]
    (bar (k/& f))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
