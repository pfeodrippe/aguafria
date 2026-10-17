(ns aguafria.zig.discovery-context-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn narrow :u8
  [[value :u16]]
  (u8 (intCast value)))

(a/defn do-not-call :noreturn
  []
  (panic "discovery must not execute this function"))

(a/deftest do-not-execute
  (let [value (u16 7)]
    (= :_ (narrow value))
    (= :_ (+ value 1)))
  (unreachable))
