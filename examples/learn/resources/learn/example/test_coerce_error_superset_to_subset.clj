(ns learn.example.test-coerce-error-superset-to-subset
  (:require [aguafria.zig :as az]))

(az/defconst FileOpenError
  (az/type [:error-set [:AccessDenied :OutOfMemory :FileNotFound]]))

(az/defconst AllocationError
  (az/type [:error-set [:OutOfMemory]]))

;; The parameter's type admits errors absent from the return type, even though
;; this particular call passes the shared OutOfMemory member.
(az/defn- narrow-error AllocationError [[error FileOpenError]]
  error)

(az/deftest superset-to-subset-test
  (catch (narrow-error (az/field FileOpenError :OutOfMemory)) (az/block)))

(comment
  (superset-to-subset-test))
