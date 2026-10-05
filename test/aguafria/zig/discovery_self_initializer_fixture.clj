(ns aguafria.zig.discovery-self-initializer-fixture
  (:require [aguafria.zig :as a]))

(a/defstruct Threshold
  [[:minimum :f32]
   [:maximum :f32]
   [:default {:const (Threshold {:minimum 0.25 :maximum 0.75})} Threshold]])

(a/defunion Numeric {:enum? true}
  [[:int :i32]
   [:float :f64]
   [:zero {:const (Numeric {:int 0})} Numeric]])

(a/defn int-value :i32
  [[value Numeric]]
  (:int value))
