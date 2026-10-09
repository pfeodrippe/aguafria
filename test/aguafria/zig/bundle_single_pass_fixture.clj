(ns aguafria.zig.bundle-single-pass-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Point
  [[:x :i32]
   [:y :i32]])

(a/defn shift Point
  [[point Point]
   [dx :i32]]
  (Point {:x (k/+ (:x point) dx)
          :y (:y point)}))
