(ns learn.example.redundant-fqn
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defstruct json
  {:attrs #{k/pub}}
  [[:JsonValue
    {:const (az/union {:enum? true}
              [[:number :f64]
               [:boolean :bool]])}
    :type]])

(az/defn main :void []
  (debug/print "{s}\n" [(k/typeName (:JsonValue json))]))

(comment
  (main))
