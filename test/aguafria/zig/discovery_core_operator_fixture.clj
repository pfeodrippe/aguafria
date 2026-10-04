(ns aguafria.zig.discovery-core-operator-fixture
  (:require [aguafria.zig :as a]))

(a/defn add :f32
  [[left :f32] [right :f32]]
  (+ left right))

(a/defn subtract :i32
  [[left :i32] [right :i32]]
  (- left right))

(a/defn negate :i32
  [[number :i32]]
  (- number))

(a/defn multiply :f32
  [[left :f32] [right :f32]]
  (* left right))

(a/defn divide :f32
  [[left :f32] [right :f32]]
  (/ left right))

(a/defn remainder :i32
  [[left :i32] [right :i32]]
  (mod left right))

(a/defn raw-add :f32
  [[left :f32] [right :f32]]
  (a/op "+" left right))
