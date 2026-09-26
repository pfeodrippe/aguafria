(ns learn.example.test-coerce-error-superset-to-subset
  (:require [aguafria.zig :as az]))

(az/defconst FileOpenError
  (az/type [:error-set [:AccessDenied :OutOfMemory :FileNotFound]]))

(az/defconst AllocationError
  (az/type [:error-set [:OutOfMemory]]))

(az/defn- foo AllocationError [[err FileOpenError]]
  err)

(az/deftest coerce-superset-to-subset
  (catch (foo (:OutOfMemory FileOpenError)) (az/block)))

(comment
  (coerce-superset-to-subset))
