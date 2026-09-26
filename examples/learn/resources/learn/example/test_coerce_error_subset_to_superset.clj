(ns learn.example.test-coerce-error-subset-to-superset
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as az]))

(az/defconst FileOpenError
  (az/type [:error-set [:AccessDenied :OutOfMemory :FileNotFound]]))

(az/defconst AllocationError
  (az/type [:error-set [:OutOfMemory]]))

(az/defn- foo FileOpenError [[err AllocationError]]
  err)

(az/deftest coerce-subset-to-superset
  (let [err (foo (:OutOfMemory AllocationError))]
    (try (testing/expectEqual (:OutOfMemory FileOpenError) err))))

(comment
  (coerce-subset-to-superset))
