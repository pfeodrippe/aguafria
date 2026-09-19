(ns learn.examples.idiomatic-values.float-special-values
  (:require aguafria.std
            [aguafria.std.math :as math]
            [aguafria.zig :as az]))

(az/defconst inf (math/inf :f32))
(az/defconst negative-inf (- (math/inf :f64)))
(az/defconst nan (math/nan :f128))
