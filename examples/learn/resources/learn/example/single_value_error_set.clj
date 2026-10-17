(ns learn.example.single-value-error-set
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defconst err
  (:FileNotFound (a/type [:error-set [:FileNotFound]])))
