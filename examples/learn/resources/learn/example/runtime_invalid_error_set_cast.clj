(ns learn.example.runtime-invalid-error-set-cast
  (:require [aguafria.keyword :as k]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Set1 (az/type [:error-set [:A :B]]))
(az/defconst Set2 (az/type [:error-set [:A :C]]))

(az/defn- foo :void [[set1 Set1]]
  (let [x (k/as (k/errorCast set1) Set2)]
    (debug/print "value: {}\n" [x])))

(az/defn main :void []
  (foo (:B Set1)))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
