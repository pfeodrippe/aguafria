(ns aguafria.zig.discovery-literals-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst failure (a/error-value :ExampleFailure))

(a/deftest never-run
  (try (testing/expectEqual :comptime_int (k/TypeOf (k/+ 1 2))))
  (k/= :_ (k/== :.ready :.ready))
  (debug/print "literal={any}\n" [:.ready])
  (k/= :_ (a/number-literal "0o755"))
  (k/= :_ (a/char-literal "'\\x65'"))
  (k/= :_ (a/string-literal "\"h\\x65llo\""))
  (k/= :_ (a/enum-literal ".@\"with space\""))
  (k/unreachable))
