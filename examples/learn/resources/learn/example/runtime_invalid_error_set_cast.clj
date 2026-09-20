(ns learn.example.runtime-invalid-error-set-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Set1 (az/type [:error-set [:A :B]]))
(az/defconst Set2 (az/type [:error-set [:A :C]]))

(az/defn- cast-error :void [[error Set1]]
  (let [casted-error (ak/as (ak/errorCast error) Set2)]
    (debug/print "value: {}\n" [casted-error])))

(az/defn main :void []
  (cast-error (az/field Set1 :B)))

(comment
  ;; This deliberately triggers native safety failure; it can terminate this JVM.
  (main))
