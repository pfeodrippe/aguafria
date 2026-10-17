(ns learn.example.single-value-error-set-shortcut
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defconst err (a/error-value :FileNotFound))
