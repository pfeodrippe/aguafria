(ns learn.example.test-comptime-invalid-error-set-cast
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defconst Set1 (a/type [:error-set [:A :B]]))
(a/defconst Set2 (a/type [:error-set [:A :C]]))

(a/defcomptime reject-incompatible-error
  (k/= :_ (k/as (k/errorCast (:B Set1)) Set2)))
