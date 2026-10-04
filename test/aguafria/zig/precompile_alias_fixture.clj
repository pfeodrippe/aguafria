(ns aguafria.zig.precompile-alias-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.c :as c]
            [aguafria.std.mem :as mem]
            [aguafria.zig :as a]))

(a/defn monotonic-status :c_int
  []
  (let [timestamp (k/var (mem/zeroes (a/type c/timespec)))]
    (c/clock_gettime :.MONOTONIC (k/& timestamp))))

(a/defn sleep-status :c_int
  []
  (let [duration (k/var (mem/zeroes (a/type c/timespec)))]
    (c/nanosleep (k/& duration) nil)))
