(ns aguafria.zig.precompile-module-type-leaf-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.zig :as a]
            [aguafria.zig.precompile-member-leaf-fixture :as constants]))

(a/defconst Self (This))

(a/deffield value :u32)

(a/defn read-value :u32 [[self Self]]
  (+ (:value self) constants/selected-value))
