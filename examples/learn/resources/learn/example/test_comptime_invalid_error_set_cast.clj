(ns learn.example.test-comptime-invalid-error-set-cast
  (:require [aguafria.keyword :as ak]
            [aguafria.zig :as az]))

(az/defconst Set1 (az/type [:error-set [:A :B]]))
(az/defconst Set2 (az/type [:error-set [:A :C]]))

(az/defcomptime reject-incompatible-error
  (ak/= :_ (ak/as (ak/errorCast (az/field Set1 :B)) Set2)))
