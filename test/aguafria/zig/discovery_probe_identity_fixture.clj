(ns aguafria.zig.discovery-probe-identity-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defstruct Choice
  [[:value :i32]
   (a/fn first Choice [[input :i32]]
     (Choice {:value input}))])

(a/defstruct Choices
  [[:Entry {:const Choice} :type]])

(a/defn typed-member-local :i32
  [[input :i32]]
  (k/const choice (a/field Choices :Entry)
           (a/with-block :result
             (k/break :result (:.first input))))
  (k/+ (:value choice) 10))
