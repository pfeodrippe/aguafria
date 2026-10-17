(ns learn.example.null
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defconst optional-value [:optional :i32] nil)
