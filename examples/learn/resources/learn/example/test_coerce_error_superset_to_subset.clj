(ns learn.example.test-coerce-error-superset-to-subset
  (:require [aguafria.zig :as a]))

(a/defconst FileOpenError
  (a/type [:error-set [:AccessDenied :OutOfMemory :FileNotFound]]))

(a/defconst AllocationError
  (a/type [:error-set [:OutOfMemory]]))

(a/defn- foo AllocationError [[err FileOpenError]]
  err)

(a/deftest coerce-superset-to-subset
  (catch (foo (:OutOfMemory FileOpenError)) (a/block)))

(comment
  (coerce-superset-to-subset))
