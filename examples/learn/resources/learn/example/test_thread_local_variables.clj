(ns learn.example.test-thread-local-variables
  (:require [aguafria.keyword :as k]
            [aguafria.std.Thread :as thread]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defvar x :i32
  {:attrs #{k/threadlocal}}
  1234)

(az/defn- testTls :void
  []
  (debug/assert (k/== x 1234))
  (k/+= x 1)
  (debug/assert (k/== x 1235)))

(az/deftest thread-local-storage
  (let [thread1 (try (thread/spawn {} testTls []))
        thread2 (try (thread/spawn {} testTls []))]
    (testTls)
    ((:join thread1))
    ((:join thread2))))

(comment
  (thread-local-storage))
