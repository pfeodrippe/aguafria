(ns aguafria.zig.discovery-callback-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.Thread :as thread]
            [aguafria.zig :as a]))

(a/defvar observed :usize 0)

(a/defn worker :void
  [[value :usize]]
  (k/= observed value))

(a/defn read-observed :usize
  []
  observed)

(a/defn launch :!void
  [[value :usize]]
  (let [handle (try (thread/spawn {:stack_size 1048576} worker [value]))]
    ((:join handle))))
