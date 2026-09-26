(ns learn.example.struct-default-value
  (:require [aguafria.zig :as az]))

(az/defstruct Threshold
  [[:minimum :f32]
   [:maximum :f32]
   [:default {:const (Threshold {:minimum 0.25 :maximum 0.75})} Threshold]])
