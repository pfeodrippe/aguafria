(ns learn.example.redundant-fqn
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defstruct json
  {:attrs #{k/pub}}
  [[:JsonValue
    {:const (a/union {:enum? true}
                     [[:number :f64]
                      [:boolean :bool]
               ;; ...
                      ])}
    :type]])

(a/defn main :void []
  (debug/print "{s}\n" [(k/typeName (:JsonValue json))]))

(comment
  (main))
