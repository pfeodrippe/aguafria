(ns aguafria.zig.discovery-context-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn narrow :u8
  [[value :u16]]
  (k/u8 (k/intCast value)))

(a/defn do-not-call :noreturn
  []
  (k/panic "discovery must not execute this function"))

(a/deftest do-not-execute
  (let [value (k/u16 7)]
    (k/= :_ (narrow value))
    (k/= :_ (k/+ value 1)))
  (k/unreachable))
