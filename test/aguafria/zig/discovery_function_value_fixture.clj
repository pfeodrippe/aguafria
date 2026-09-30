(ns aguafria.zig.discovery-function-value-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defstruct Command
  [[:function [:fn {} [{:type :i32}] :i32]]])

(az/defn increment :i32
  [[value :i32]]
  (k/+ value 1))

(az/defconst command (Command {:function increment}))

(az/defn invoke-command :i32
  [[value :i32]]
  ((:function command) value))
