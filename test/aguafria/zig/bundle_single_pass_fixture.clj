(ns aguafria.zig.bundle-single-pass-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Point
  [[:x :i32]
   [:y :i32]])

(a/defn shift Point
  [[point Point]
   [dx :i32]]
  (Point {:x (+ (:x point) dx)
          :y (:y point)}))
