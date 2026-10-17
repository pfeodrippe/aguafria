(ns aguafria.zig.namespace-admission-invalid-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]))

(a/defn invalid-add :i32 [[x :i32]]
  (+ x true))
