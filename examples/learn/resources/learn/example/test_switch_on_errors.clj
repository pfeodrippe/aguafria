(ns learn.example.test-switch-on-errors
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst FileOpenError0
  (az/type [:error-set [:AccessDenied :OutOfMemory :FileNotFound]]))

(az/defn- open-file-0 FileOpenError0 []
  (az/error-value :OutOfMemory))

(az/deftest unreachable-else-prong
  (az/switch-stmt (open-file-0)
                  (case [(az/error-value :AccessDenied) (az/error-value :FileNotFound)] [e]
                        (k/return e))
                  (case [(az/error-value :OutOfMemory)] (az/block))
    ;; 'openFile0' cannot return any more errors, so an 'else' prong would be
    ;; statically known to be unreachable. Nonetheless, in this case, adding
    ;; one does not raise an "unreachable else prong" compile error:
                  (az/case-else (k/unreachable)))
  ;; Allowed unreachable else prongs are:
  ;;    `else => unreachable,`
  ;;    `else => return,`
  ;;    `else => |e| return e,` (where `e` is any identifier)
  )

(az/defconst FileOpenError1
  (az/type [:error-set [:AccessDenied :SystemResources :FileNotFound]]))

(az/defn- open-file-1 FileOpenError1 []
  (az/error-value :SystemResources))

(az/defn- open-file-generic
  (switch kind (case [0] FileOpenError0) (case [1] FileOpenError1))
  [[kind {:attrs #{k/comptime}} :u1]]
  (switch kind (case [0] (open-file-0)) (case [1] (open-file-1))))

(az/deftest comptime-unreachable-errors-not-in-error-set
  (az/switch-stmt (open-file-generic 1)
                  (case [(az/error-value :AccessDenied) (az/error-value :FileNotFound)] [e]
                        (k/return e))
                  (case [(az/error-value :OutOfMemory)] (k/comptime (k/unreachable))) ; not in `FileOpenError1`!
                  (case [(az/error-value :SystemResources)] (az/block))))

(comment
  (unreachable-else-prong)
  (comptime-unreachable-errors-not-in-error-set))
