(ns learn.example.float-special-values
  (:refer-clojure :only [])
  (:require [aguafria.keyword :as k :refer :all]
            [aguafria.std.math :as math]
            [aguafria.zig :as a]))

(a/defconst inf (math/inf :f32))
(a/defconst negative-inf (- (math/inf :f64)))
(a/defconst nan (math/nan :f128))
