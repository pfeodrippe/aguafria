(ns learn.examples.idiomatic-error-flow.coerce-error-subset-to-superset
  "Converted from test_coerce_error_subset_to_superset.zig"
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst FileOpenError
  (az/type [:error-set [:AccessDenied :OutOfMemory :FileNotFound]]))

(az/defconst AllocationError
  (az/type [:error-set [:OutOfMemory]]))

(az/defn- widen-error FileOpenError [[error AllocationError]]
  error)

(az/deftest subset-to-superset-test
  (let [error (widen-error (az/field AllocationError :OutOfMemory))]
    (try (testing/expectEqual (az/field FileOpenError :OutOfMemory) error))))
