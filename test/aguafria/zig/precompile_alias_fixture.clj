(ns aguafria.zig.precompile-alias-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.c :as libc]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]))

(a/defn monotonic-status :c_int
  []
  (let [timestamp (k/var (mem/zeroes (a/type libc/timespec)))]
    (libc/clock_gettime :MONOTONIC (k/& timestamp))))

(a/defn sleep-status :c_int
  []
  (let [duration (k/var (mem/zeroes (a/type libc/timespec)))]
    (libc/nanosleep (k/& duration) nil)))
