(ns learn.example.runtime-wrong-union-field-access
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/union
    [[:float :f32]
     [:int :u32]]))

(az/defn- bar :void [[f [:* Foo]]]
  (ak/= (az/field f :float) 12.34)
  (debug/print "value: {}\n" [(az/field f :float)]))

(az/defn main :void []
  (let [f (ak/var (Foo {:int 42}))]
    (bar (ak/& f))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
