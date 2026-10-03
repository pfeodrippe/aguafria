(ns aguafria.zig.discovery-tuple-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/deftest never-run
  (let [number (k/i32 42)]
    (debug/print "number={} literal={} boolean={}\n" [number 7 true]))
  (debug/print "quoted=\"{}\"\tλ\n" [(k/f32 1.25)])
  (k/= :_ (k/== :i32 :i32))
  (k/unreachable))
