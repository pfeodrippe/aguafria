(ns learn.example.test-coerce-error-subset-to-superset
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst FileOpenError
  (az/type [:error-set [:AccessDenied :OutOfMemory :FileNotFound]]))

(az/defconst AllocationError
  (az/type [:error-set [:OutOfMemory]]))

(az/defn- foo FileOpenError [[error AllocationError]]
  error)

(az/deftest subset-to-superset-test
  (let [error (foo (:OutOfMemory AllocationError))]
    (try (testing/expectEqual (:OutOfMemory FileOpenError) error))))

(comment
  (subset-to-superset-test))
