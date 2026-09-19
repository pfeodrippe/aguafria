(ns learn.examples.idiomatic-safety.runtime-invalid-error-set-cast
  "Converted from runtime_invalid_error_set_cast.zig"
  (:require [aguafria.keyword :as ak]
            [aguafria.std.debug :as debug]
            [aguafria.zig :as az]))

(az/defconst Set1 (az/type [:error-set [:A :B]]))
(az/defconst Set2 (az/type [:error-set [:A :C]]))

(az/defn- cast-error :void [[error Set1]]
  (let [^{:zig/type Set2} casted-error (ak/errorCast error)]
    (debug/print "value: {}\n" [casted-error])))

(az/defn main :void []
  (cast-error (az/field Set1 :B)))
