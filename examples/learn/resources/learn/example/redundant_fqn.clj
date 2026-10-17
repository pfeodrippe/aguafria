(ns learn.example.redundant-fqn
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defstruct json
  {:attrs #{pub}}
  [[:JsonValue
    {:const (a/union {:enum? true}
                     [[:number :f64]
                      [:boolean :bool]
               ;; ...
                      ])}
    :type]])

(a/defn main :void []
  (debug/print "{s}\n" [(typeName (:JsonValue json))]))

(comment
  (main))
