(ns learn.example.float-special-values
  (:require [aguafria.keyword :as k]
            [aguafria.std.math :as math]
            [aguafria.zig :as az]))

(az/defconst inf (math/inf :f32))
(az/defconst negative-inf (k/- (math/inf :f64)))
(az/defconst nan (math/nan :f128))
