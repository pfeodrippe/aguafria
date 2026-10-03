(ns learn.example.struct-default-value
  (:require [aguafria.zig :as a]))

(a/defstruct Threshold
  [[:minimum :f32]
   [:maximum :f32]
   [:default {:const (Threshold {:minimum 0.25 :maximum 0.75})} Threshold]])
