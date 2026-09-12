(ns field-lab.contacts
  "Small multi-body solver: packed numerical values, independent of Flecs."
  (:require [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.physics :as p]))

(az/defstruct Sample {:layout :extern} [[:bodies [:array 3 p/State]]])

(az/defn resolve-pair!
  "Frictional sphere contact with angular effective mass and overlap correction."
  :- :void
  [[a [:* p/State]] [b [:* p/State]] [config p/Config]]
  (let [delta (p/add (az/field (az/deref b) position)
                     (p/scale (az/field (az/deref a) position) -1.0))
        distance (p/length delta)
        diameter (* 2.0 (az/field config radius))]
    (when (> distance diameter) (ak/return))
    (let [normal (if (> distance 1.0e-12) (p/scale delta (/ 1.0 distance)) (p/v 1.0 0.0 0.0))
          correction (p/scale normal (* 0.5 (ak/max 0.0 (- diameter distance))))
          arm-a (p/scale normal (az/field config radius))
          arm-b (p/scale arm-a -1.0)
          velocity-a (p/add (az/field (az/deref a) velocity)
                            (p/cross (az/field (az/deref a) omega) arm-a))
          velocity-b (p/add (az/field (az/deref b) velocity)
                            (p/cross (az/field (az/deref b) omega) arm-b))
          relative (p/add velocity-b (p/scale velocity-a -1.0))
          normal-speed (p/dot relative normal)
          inverse-mass (/ 1.0 (az/field config mass))]
      (az/set-many!
        (az/field (az/deref a) position)
        (p/add (az/field (az/deref a) position)
               (p/scale correction -1.0))
        (az/field (az/deref b) position)
        (p/add (az/field (az/deref b) position)
               correction))
      (when (< normal-speed -1.0e-10)
        (let [normal-impulse (/ (* (- normal-speed) (+ 1.0 (az/field config restitution)))
                                (* 2.0 inverse-mass))
              tangent (p/add relative (p/scale normal (- normal-speed)))
              slip (p/length tangent)
              effective (+ (* 2.0 inverse-mass)
                           (/ (* 2.0 (az/field config radius) (az/field config radius))
                              (p/inertia config)))
              friction-impulse (ak/min (/ slip effective)
                                       (* (az/field config friction) normal-impulse))
              impulse (p/add (p/scale normal normal-impulse)
                             (if (> slip 1.0e-12)
                               (p/scale tangent (/ (- friction-impulse) slip))
                               (p/v 0.0 0.0 0.0)))]
          (az/set-many!
            (az/field (az/deref a) velocity)
            (p/add (az/field (az/deref a) velocity)
                   (p/scale impulse
                            (- inverse-mass)))
            (az/field (az/deref b) velocity)
            (p/add (az/field (az/deref b) velocity)
                   (p/scale impulse inverse-mass))
            (az/field (az/deref a) omega)
            (p/add (az/field (az/deref a) omega)
                   (p/scale (p/cross arm-a impulse) (/ -1.0 (p/inertia config))))
            (az/field (az/deref b) omega)
            (p/add (az/field (az/deref b) omega)
                   (p/scale (p/cross arm-b impulse) (/ 1.0 (p/inertia config))))
            (az/field (az/deref a) impacts) (+ (az/field (az/deref a) impacts) 1)
            (az/field (az/deref b) impacts) (+ (az/field (az/deref b) impacts) 1)
            (az/field (az/deref a) supported) false
            (az/field (az/deref b) supported) false))))))

(az/defn advance
  "Eight collision substeps at the supported interactive velocity/radius range.
  Floor collisions use the single-ball CCD solver. Pair contacts are discrete."
  :- Sample
  [[input Sample] [config p/Config] [body-count :u32] [dt :f64]]
  (let [^:var result input
        ^{:var [:array 3 :f64]} floor-impulses [0.0 0.0 0.0]
        step (/ dt 8.0)]
    (dotimes [_ 8]
      (dotimes [i body-count]
        (let [body (p/advance (az/index (az/field result bodies) i) config step)]
          (az/set-many!
            (az/index (az/field result bodies) i) body
            (az/index floor-impulses i)
            (+ (az/index floor-impulses i)
               (az/field body impulse)))))
      (dotimes [_ 4]
        (dotimes [i body-count]
          (dotimes [j body-count]
            (when (< i j)
              (resolve-pair! (ak/& (az/index (az/field result bodies) i))
                             (ak/& (az/index (az/field result bodies) j))
                             config))))
        (dotimes [i body-count]
          (let [body (ak/& (az/index (az/field result bodies) i))]
            (set! (az/field (az/field (az/deref body) position) y)
                  (ak/max (az/field config radius)
                          (az/field (az/field (az/deref body) position) y)))))))
    (dotimes [i body-count]
      (set! (az/field (az/index (az/field result bodies) i) impulse)
            (az/index floor-impulses i)))
    result))
