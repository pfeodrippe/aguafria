(ns aguafria.zig.precompile-eager-constants-fixture
  (:refer-clojure :only [])
  (:require [aguafria.zig :as a]
            [aguafria.keyword :as k :refer :all]))

(a/defconst selected :u32 11)
(a/defconst required-a 3)
(a/defconst required-b 7)
