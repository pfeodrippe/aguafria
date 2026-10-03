(ns learn.example.stack-trace
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn- bang1 :bool []
  false)

(a/defn- bang2 :void []
  (k/panic "PermissionDenied"))

(a/defn- baz :bool []
  (bang1))

(a/defn- quux :void []
  (bang2))

(a/defn- hello :void []
  (bang2))

(a/defn- bar :void []
  (if (baz)
    (quux)
    (hello)))

(a/defn- foo :void [[x :i32]]
  (if (k/>= x 5)
    (bar)
    (bang2)))

(a/defn main :void []
  (foo 12))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
