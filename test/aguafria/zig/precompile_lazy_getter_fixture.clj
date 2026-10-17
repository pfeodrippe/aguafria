(ns aguafria.zig.precompile-lazy-getter-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defstruct Box [[:value :u32]])

(a/defn first-value :u32 [] 4)

(a/defn last-value :u32 [] 5)

(a/defn box-value Box [] (Box {:value 6}))
