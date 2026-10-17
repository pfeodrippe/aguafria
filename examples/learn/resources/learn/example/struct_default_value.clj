(ns learn.example.struct-default-value
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defstruct Threshold
  [[:minimum :f32]
   [:maximum :f32]
   [:default {:const (Threshold {:minimum 0.25 :maximum 0.75})} Threshold]])
