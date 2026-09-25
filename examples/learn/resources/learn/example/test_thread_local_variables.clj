(ns learn.example.test-thread-local-variables
  (:require [aguafria.keyword :as k]
            [aguafria.std.Thread :as thread]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defvar value :i32
  {:attrs #{k/threadlocal}}
  1234)

(az/defn- testTls :void
  []
  (debug/assert (k/== value 1234))
  (k/+= value 1)
  (debug/assert (k/== value 1235)))

(az/deftest thread-local-storage-test
  (let [first-thread (try (thread/spawn {} testTls []))
        second-thread (try (thread/spawn {} testTls []))]
    (testTls)
    ((az/field first-thread :join))
    ((az/field second-thread :join))))

(comment
  (thread-local-storage-test))
