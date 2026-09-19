(ns learn.examples.idiomatic-state.thread-local-variables
  (:require aguafria.std
            [aguafria.keyword :as ak]
            [aguafria.std.Thread :as thread]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defvar value
  {:zig/prefix "threadlocal"}
  :i32 1234)

(az/deftest thread-local-storage-test
  (let [first-thread (try (thread/spawn {} check-thread-local []))
        second-thread (try (thread/spawn {} check-thread-local []))]
    (check-thread-local)
    ((az/field first-thread :join))
    ((az/field second-thread :join))))

(az/defn- check-thread-local :void
  []
  (debug/assert (== value 1234))
  (ak/+= value 1)
  (debug/assert (== value 1235)))
