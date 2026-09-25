(ns learn.example.redundant-fqn
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defstruct json
  {:attrs #{ak/pub}}
  [[:JsonValue
    {:const (az/union {:enum? true}
              [[:number :f64]
               [:boolean :bool]])}
    :type]])

(az/defn main :void []
  (debug/print "{s}\n" [(ak/typeName (az/field json :JsonValue))]))

(comment
  (main))
