(ns aguafria.zig.precompile-lazy-reference-leaf-fixture
  (:require [aguafria.zig :as a]))

(a/defenum Command [:version :other])

(a/defn native-id :u32
  [[x :u32]]
  x)
