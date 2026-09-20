(ns learn.example.float-special-values
  (:require [aguafria.std.math :as math]
            [aguafria.zig :as az]))

(az/defconst inf (math/inf :f32))
(az/defconst negative-inf (- (math/inf :f64)))
(az/defconst nan (math/nan :f128))

(comment
  ;; This excerpt has no standalone entry point; evaluate its declarations above in their documented context.
  )
