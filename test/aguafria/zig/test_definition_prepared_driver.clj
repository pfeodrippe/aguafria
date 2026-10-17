(ns aguafria.zig.test-definition-prepared-driver)

;; Keep the worker entry separate from the lesson namespace, as the editor
;; workload does. Requiring this driver must not preload or execute the lesson.
(defn loaded [] :loaded)
