(ns aguafria.zig.precompile-callable-snapshot-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.Thread :as thread]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defvar counter :i32 {:attrs #{threadlocal}} 1234)

(a/defn- callback :void []
  (debug/assert (== counter 1234))
  (+= counter 1)
  (debug/assert (== counter 1235)))

(a/defn current-count :i32 [] counter)

(a/deftest threads-and-direct-call
  (let [first-thread (k/try (thread/spawn {} callback []))
        second-thread (k/try (thread/spawn {} callback []))]
    (callback)
    ((:join first-thread))
    ((:join second-thread))))
