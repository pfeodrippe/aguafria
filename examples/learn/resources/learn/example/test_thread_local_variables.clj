(ns learn.example.test-thread-local-variables
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.Thread :as thread]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defvar x :i32
  {:attrs #{threadlocal}}
  1234)

(a/defn- testTls :void
  []
  (debug/assert (== x 1234))
  (+= x 1)
  (debug/assert (== x 1235)))

(a/deftest thread-local-storage
  (let [thread1 (k/try (thread/spawn {} testTls []))
        thread2 (k/try (thread/spawn {} testTls []))]
    (testTls)
    ((:join thread1))
    ((:join thread2))))

(comment
  (thread-local-storage))
