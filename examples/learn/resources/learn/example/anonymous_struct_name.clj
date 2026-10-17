(ns learn.example.anonymous-struct-name
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Node
  [[:next [:optional [:* Node]]]
   [:name [:slice-const :u8]]])

(a/defvar node-a (Node {:next nil :name "Node A"}))
(a/defvar node-b (Node {:next (k/& node-a) :name "Node B"}))
