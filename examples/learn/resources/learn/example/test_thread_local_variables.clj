(ns learn.example.test-thread-local-variables
  (:require [aguafria.keyword :as ak]
            [aguafria.std.Thread :as thread]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defvar value :i32
  {:attrs #{ak/threadlocal}}
  1234)

(az/defn- testTls :void
  []
  (debug/assert (ak/== value 1234))
  (ak/+= value 1)
  (debug/assert (ak/== value 1235)))

(az/deftest thread-local-storage-test
  (let [first-thread (try (thread/spawn {} testTls []))
        second-thread (try (thread/spawn {} testTls []))]
    (testTls)
    ((az/field first-thread :join))
    ((az/field second-thread :join))))

(comment
  (thread-local-storage-test))
