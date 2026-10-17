(ns aguafria.zig.discovery-probe-identity-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defstruct Choice
  [[:value :i32]
   (a/fn first Choice [[input :i32]]
     (Choice {:value input}))])

(a/defstruct Choices
  [[:Entry {:const Choice} :type]])

(a/defn typed-member-local :i32
  [[input :i32]]
  (const choice (a/field Choices :Entry)
         (a/with-block :result
           (break :result ((a/enum-literal ".first") input))))
  (+ (:value choice) 10))
