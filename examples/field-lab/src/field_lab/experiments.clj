(ns field-lab.experiments
  "Experiment definitions are ordinary Clojure-shaped AguaFria declarations.
  Source -> solver -> output is represented by named Flecs entities in scene.clj."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.physics :as p]
            [field-lab.contacts :as contacts]))

(az/defn single-ball
  :- contacts/Sample
  [[config p/Config]]
  (contacts/Sample {:bodies [(p/initial config) (p/initial config) (p/initial config)]}))

(az/defn three-balls
  "Three equal spheres approach a central sphere. Edit these ordinary Clojure
  forms to author a new initial condition; rendering consumes the same states."
  :- contacts/Sample
  [[config p/Config]]
  (let [^:var sample (single-ball config)
        spacing (+ (* 3.0 (az/field config radius)) 0.4)
        height (+ (az/field config radius) (az/field config height))
        speed (ak/max 0.5 (ak/abs (az/field config vx)))]
    (az/set-many!
      (az/field (az/index (az/field sample bodies) 0) position) (p/v (- spacing) height 0.0)
      (az/field (az/index (az/field sample bodies) 1) position) (p/v 0.0 height 0.18)
      (az/field (az/index (az/field sample bodies) 2) position) (p/v spacing height 0.0)
      (az/field (az/index (az/field sample bodies) 0) velocity) (p/v speed 0.0 0.0)
      (az/field (az/index (az/field sample bodies) 1) velocity) (p/v 0.0 0.0 0.0)
      (az/field (az/index (az/field sample bodies) 2) velocity) (p/v (- speed) 0.0 0.0))
    sample))
