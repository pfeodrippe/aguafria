(ns aguafria.zig.namespace-admission-context-fixture
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

;; Valid in discovery's test frontend, invalid in an ordinary JVM image.
(a/defn allocator-size :usize []
  (sizeOf (TypeOf testing/allocator)))
