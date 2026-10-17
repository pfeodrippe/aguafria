(ns learn.example.export-builtin-equivalent-code
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn foo :void
  {:attrs #{export}}
  [])

(comment
  (foo))
