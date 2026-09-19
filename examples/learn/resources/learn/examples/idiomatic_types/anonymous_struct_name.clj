(ns learn.examples.idiomatic-types.anonymous-struct-name
  (:require [aguafria.zig :as az]))

;; The declaration supplies the name used by the self-referential pointer type.
(az/defstruct Node
  [[:next [:optional [:* Node]]]
   [:name [:slice-const :u8]]])

(az/defvar node-a (Node {:next nil :name "Node A"}))
(az/defvar node-b (Node {:next (& node-a) :name "Node B"}))
