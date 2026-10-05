(ns aguafria.zig.precompile-module-type-leaf-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-member-leaf-fixture :as constants]))

(a/defconst Self (k/This))

(a/deffield value :u32)

(a/defn read-value :u32 [[self Self]]
  (k/+ (:value self) constants/selected-value))
