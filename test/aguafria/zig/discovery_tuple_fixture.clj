(ns aguafria.zig.discovery-tuple-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/deftest never-run
  (let [number (i32 42)]
    (debug/print "number={} literal={} boolean={}\n" [number 7 true]))
  (debug/print "quoted=\"{}\"\tλ\n" [(f32 1.25)])
  (= :_ (== :i32 :i32))
  (unreachable))
