(ns learn.example.test-switch-on-errors
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst FileOpenError0
  (a/type [:error-set [:AccessDenied :OutOfMemory :FileNotFound]]))

(a/defn- open-file-0 FileOpenError0 []
  (a/error-value :OutOfMemory))

(a/deftest unreachable-else-prong
  (a/switch-stmt (open-file-0)
                 (case [(a/error-value :AccessDenied) (a/error-value :FileNotFound)] [e]
                       (k/return e))
                 (case [(a/error-value :OutOfMemory)] (a/block))
    ;; 'openFile0' cannot return any more errors, so an 'else' prong would be
    ;; statically known to be unreachable. Nonetheless, in this case, adding
    ;; one does not raise an "unreachable else prong" compile error:
                 (a/case-else (k/unreachable)))
  ;; Allowed unreachable else prongs are:
  ;;    `else => unreachable,`
  ;;    `else => return,`
  ;;    `else => |e| return e,` (where `e` is any identifier)
  )

(a/defconst FileOpenError1
  (a/type [:error-set [:AccessDenied :SystemResources :FileNotFound]]))

(a/defn- open-file-1 FileOpenError1 []
  (a/error-value :SystemResources))

(a/defn- open-file-generic
  (switch kind (case [0] FileOpenError0) (case [1] FileOpenError1))
  [[kind {:attrs #{k/comptime}} :u1]]
  (switch kind (case [0] (open-file-0)) (case [1] (open-file-1))))

(a/deftest comptime-unreachable-errors-not-in-error-set
  (a/switch-stmt (open-file-generic 1)
                 (case [(a/error-value :AccessDenied) (a/error-value :FileNotFound)] [e]
                       (k/return e))
                 (case [(a/error-value :OutOfMemory)] (k/comptime (k/unreachable))) ; not in `FileOpenError1`!
                 (case [(a/error-value :SystemResources)] (a/block))))

(comment
  (unreachable-else-prong)
  (comptime-unreachable-errors-not-in-error-set))
