(ns learn.example.test-illegal-behavior
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/deftest safety-check
  (unreachable))

(comment
  (safety-check))
