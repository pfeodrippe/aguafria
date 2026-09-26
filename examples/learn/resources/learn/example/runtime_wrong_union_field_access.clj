(ns learn.example.runtime-wrong-union-field-access
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/union
    [[:float :f32]
     [:int :u32]]))

(az/defn- bar :void [[f [:* Foo]]]
  (k/= (:float f) 12.34)
  (debug/print "value: {}\n" [(:float f)]))

(az/defn main :void []
  (let [f (k/var (Foo {:int 42}))]
    (bar (k/& f))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
