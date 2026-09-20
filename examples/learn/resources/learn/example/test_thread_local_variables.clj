(ns learn.example.test-thread-local-variables
  (:require [aguafria.keyword :as ak]
            [aguafria.std.Thread :as thread]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defvar value
  {:zig/prefix "threadlocal"}
  :i32 1234)

(az/deftest thread-local-storage-test
  (let [first-thread (try (thread/spawn {} testTls []))
        second-thread (try (thread/spawn {} testTls []))]
    (testTls)
    ((az/field first-thread :join))
    ((az/field second-thread :join))))

(az/defn- testTls :void
  []
  (debug/assert (== value 1234))
  (ak/+= value 1)
  (debug/assert (== value 1235)))

(comment
  (thread-local-storage-test))
