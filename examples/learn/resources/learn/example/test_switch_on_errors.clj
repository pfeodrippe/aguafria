(ns learn.example.test-switch-on-errors
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst FileOpenError0
  (az/type [:error-set [:AccessDenied :OutOfMemory :FileNotFound]]))

(az/defn- open-file-0 FileOpenError0 []
  (az/error-value :OutOfMemory))

(az/deftest unreachable-else-prong-test
  (az/switch-stmt (open-file-0)
    (case [(az/error-value :AccessDenied) (az/error-value :FileNotFound)] [error]
      (ak/return error))
    (case [(az/error-value :OutOfMemory)] (az/block))
    ;; This exact else target is allowed even when all errors are covered.
    (az/case-else (ak/unreachable))))

(az/defconst FileOpenError1
  (az/type [:error-set [:AccessDenied :SystemResources :FileNotFound]]))

(az/defn- open-file-1 FileOpenError1 []
  (az/error-value :SystemResources))

(az/defn- open-file-generic
  (switch kind (case [0] FileOpenError0) (case [1] FileOpenError1))
  [[kind {:zig/prefix "comptime"} :u1]]
  (switch kind (case [0] (open-file-0)) (case [1] (open-file-1))))

(az/deftest comptime-unreachable-error-test
  (az/switch-stmt (open-file-generic 1)
    (case [(az/error-value :AccessDenied) (az/error-value :FileNotFound)] [error]
      (ak/return error))
    ;; OutOfMemory is absent from this instantiation's error set. Preserve
    ;; the exact comptime-unreachable form that permits this prong.
    (case [(az/error-value :OutOfMemory)] (ak/comptime (ak/unreachable)))
    (case [(az/error-value :SystemResources)] (az/block))))

(comment
  (unreachable-else-prong-test)
  (comptime-unreachable-error-test))
