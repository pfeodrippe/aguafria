(ns learn.example.test-coerce-error-subset-to-superset
  (:require [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

(a/defconst FileOpenError
  (a/type [:error-set [:AccessDenied :OutOfMemory :FileNotFound]]))

(a/defconst AllocationError
  (a/type [:error-set [:OutOfMemory]]))

(a/defn- foo FileOpenError [[err AllocationError]]
  err)

(a/deftest coerce-subset-to-superset
  (let [err (foo (:OutOfMemory AllocationError))]
    (try (testing/expectEqual (:OutOfMemory FileOpenError) err))))

(comment
  (coerce-subset-to-superset))
