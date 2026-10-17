(ns aguafria.zig.namespace-admission-valid-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn increment :i32 [[x :i32]]
  (+ x 1))
