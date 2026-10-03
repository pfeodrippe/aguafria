(ns learn.example.float-special-values
  (:require [aguafria.keyword :as k]
            [aguafria.std.math :as math]
            [aguafria.zig :as a]))

(a/defconst inf (math/inf :f32))
(a/defconst negative-inf (k/- (math/inf :f64)))
(a/defconst nan (math/nan :f128))
