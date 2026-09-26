(ns learn.example.stack-trace
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defn- bang1 :bool []
  false)

(az/defn- bang2 :void []
  (k/panic "PermissionDenied"))

(az/defn- baz :bool []
  (bang1))

(az/defn- quux :void []
  (bang2))

(az/defn- hello :void []
  (bang2))

(az/defn- bar :void []
  (if (baz)
    (quux)
    (hello)))

(az/defn- foo :void [[x :i32]]
  (if (k/>= x 5)
    (bar)
    (bang2)))

(az/defn main :void []
  (foo 12))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
