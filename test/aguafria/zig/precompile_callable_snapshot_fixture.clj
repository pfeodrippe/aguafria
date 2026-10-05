(ns aguafria.zig.precompile-callable-snapshot-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.Thread :as thread]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defvar counter :i32 {:attrs #{k/threadlocal}} 1234)

(a/defn- callback :void []
  (debug/assert (k/== counter 1234))
  (k/+= counter 1)
  (debug/assert (k/== counter 1235)))

(a/defn current-count :i32 [] counter)

(a/deftest threads-and-direct-call
  (let [first-thread (k/try (thread/spawn {} callback []))
        second-thread (k/try (thread/spawn {} callback []))]
    (callback)
    ((:join first-thread))
    ((:join second-thread))))
