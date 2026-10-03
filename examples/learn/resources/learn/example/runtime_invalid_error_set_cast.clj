(ns learn.example.runtime-invalid-error-set-cast
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as a]))

(a/defconst Set1 (a/type [:error-set [:A :B]]))
(a/defconst Set2 (a/type [:error-set [:A :C]]))

(a/defn- foo :void [[set1 Set1]]
  (let [x (k/as (k/errorCast set1) Set2)]
    (debug/print "value: {}\n" [x])))

(a/defn main :void []
  (foo (:B Set1)))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
