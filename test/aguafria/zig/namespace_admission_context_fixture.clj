(ns aguafria.zig.namespace-admission-context-fixture
  (:require [aguafria.keyword :as k]
            [aguafria.std.testing :as testing]
            [aguafria.zig :as a]))

;; Valid in discovery's test frontend, invalid in an ordinary JVM image.
(a/defn allocator-size :usize []
  (k/sizeOf (k/TypeOf testing/allocator)))
