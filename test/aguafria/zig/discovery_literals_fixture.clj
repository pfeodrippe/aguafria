(ns aguafria.zig.discovery-literals-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst failure (az/error-value :ExampleFailure))

(az/deftest never-run
  (try (testing/expectEqual :comptime_int (k/TypeOf (k/+ 1 2))))
  (k/= :_ (k/== :.ready :.ready))
  (debug/print "literal={any}\n" [:.ready])
  (k/= :_ (az/number-literal "0o755"))
  (k/= :_ (az/char-literal "'\\x65'"))
  (k/= :_ (az/string-literal "\"h\\x65llo\""))
  (k/= :_ (az/enum-literal ".@\"with space\""))
  (k/unreachable))
