(ns aguafria.zig.discovery-context-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn narrow :u8
  [[value :u16]]
  (k/u8 (k/intCast value)))

(az/defn do-not-call :noreturn
  []
  (k/panic "discovery must not execute this function"))

(az/deftest do-not-execute
  (let [value (k/u16 7)]
    (k/= :_ (narrow value))
    (k/= :_ (k/+ value 1)))
  (k/unreachable))
