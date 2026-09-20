(ns learn.example.redundant-fqn
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defstruct json
  {:attrs #{:public}}
  [(az/const-decl JsonValue {:attrs #{:public}}
     (az/union {:enum? true}
       [[:number :f64]
        [:boolean :bool]]))])

(az/defn main :void []
  (debug/print "{s}\n" [(ak/typeName (az/field json :JsonValue))]))

(comment
  (main))
