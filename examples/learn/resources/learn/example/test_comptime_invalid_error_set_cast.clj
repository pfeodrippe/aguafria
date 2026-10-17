(ns learn.example.test-comptime-invalid-error-set-cast
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defconst Set1 (a/type [:error-set [:A :B]]))
(a/defconst Set2 (a/type [:error-set [:A :C]]))

(a/defcomptime reject-incompatible-error
  (= :_ (as (errorCast (:B Set1)) Set2)))
