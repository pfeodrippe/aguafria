(ns learn.examples.idiomatic-values.float-special-values
  "Converted from float_special_values.zig"
  (:require aguafria.std
            [aguafria.std.math :as math]
            [aguafria.zig :as az]))

(az/defconst inf (math/inf :f32))
(az/defconst negative-inf (- (math/inf :f64)))
(az/defconst nan (math/nan :f128))
