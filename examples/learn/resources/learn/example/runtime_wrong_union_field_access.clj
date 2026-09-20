(ns learn.example.runtime-wrong-union-field-access
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Foo
  (az/union
    [[:float :f32]
     [:int :u32]]))

(az/defn- overwrite-inactive-field :void [[value [:* Foo]]]
  (set! (az/field value :float) 12.34)
  (debug/print "value: {}\n" [(az/field value :float)]))

(az/defn main :void []
  (let [^:var value (az/init Foo {:int 42})]
    (overwrite-inactive-field (ak/& value))))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
