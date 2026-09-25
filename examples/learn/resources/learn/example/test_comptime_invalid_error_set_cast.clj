(ns learn.example.test-comptime-invalid-error-set-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as az]))

(az/defconst Set1 (az/type [:error-set [:A :B]]))
(az/defconst Set2 (az/type [:error-set [:A :C]]))

(az/defcomptime reject-incompatible-error
  (k/= :_ (k/as (k/errorCast (az/field Set1 :B)) Set2)))
