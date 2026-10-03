(ns aguafria.zig.discovery-function-value-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Command
  [[:function [:fn {} [{:type :i32}] :i32]]])

(a/defn increment :i32
  [[value :i32]]
  (k/+ value 1))

(a/defconst command (Command {:function increment}))

(a/defn invoke-command :i32
  [[value :i32]]
  ((:function command) value))
