(ns aguafria.zig.discovery-function-value-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Command
  [[:function [:fn {} [{:type :i32}] :i32]]])

(a/defn increment :i32
  [[value :i32]]
  (+ value 1))

(a/defconst command (Command {:function increment}))

(a/defn invoke-command :i32
  [[value :i32]]
  ((:function command) value))
