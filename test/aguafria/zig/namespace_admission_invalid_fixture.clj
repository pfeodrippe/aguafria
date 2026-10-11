(ns aguafria.zig.namespace-admission-invalid-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]))

(a/defn invalid-add :i32 [[x :i32]]
  (k/+ x true))
