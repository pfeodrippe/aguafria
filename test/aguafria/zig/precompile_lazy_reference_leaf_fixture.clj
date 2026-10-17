(ns aguafria.zig.precompile-lazy-reference-leaf-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defenum Command [:version :other])

(a/defn native-id :u32
  [[x :u32]]
  x)
