(ns learn.example.test-coerce-error-superset-to-subset
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defconst FileOpenError
  (a/type [:error-set [:AccessDenied :OutOfMemory :FileNotFound]]))

(a/defconst AllocationError
  (a/type [:error-set [:OutOfMemory]]))

(a/defn- foo AllocationError [[err FileOpenError]]
  err)

(a/deftest coerce-superset-to-subset
  (aguafria.keyword/catch (foo (:OutOfMemory FileOpenError)) (a/block)))

(comment
  (coerce-superset-to-subset))
