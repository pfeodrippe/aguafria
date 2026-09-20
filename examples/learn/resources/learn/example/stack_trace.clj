(ns learn.example.stack-trace
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defn main :void []
  (foo 12))

(az/defn- foo :void [[value :i32]]
  (if (>= value 5)
    (bar)
    (bang2)))

(az/defn- bar :void []
  (if (baz)
    (quux)
    (hello)))

(az/defn- baz :bool []
  (bang1))

(az/defn- quux :void []
  (bang2))

(az/defn- hello :void []
  (bang2))

(az/defn- bang1 :bool []
  false)

(az/defn- bang2 :void []
  (ak/panic "PermissionDenied"))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
