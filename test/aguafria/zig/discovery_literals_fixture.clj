(ns aguafria.zig.discovery-literals-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.debug :as debug]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst failure (a/error-value :ExampleFailure))

(a/deftest never-run
  (try (testing/expectEqual :comptime_int (TypeOf (+ 1 2))))
  (= :_ (== :ready :ready))
  (debug/print "literal={any}\n" [:ready])
  (= :_ (a/number-literal "0o755"))
  (= :_ (a/char-literal "'\\x65'"))
  (= :_ (a/string-literal "\"h\\x65llo\""))
  (= :_ (a/enum-literal ".@\"with space\""))
  (unreachable))
