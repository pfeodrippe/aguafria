(ns field-lab.soft-body
  "XPBD tetrahedral sphere. Positions, velocities and contacts are simulated."
  (:require [aguafria.std]
            [aguafria.std.mem :as mem]
            [aguafria.keyword :as ak]
            [aguafria.zig :as az]
            [field-lab.physics :as p]
            [field-lab.soft-mesh :as mesh]))

(az/defconst particle-count :usize 43)

(az/defconst edge-count :usize 162)

(az/defconst face-count :usize 80)

(az/defstruct Body
  {:layout :extern}
  [[:positions [:array 43 p/Vec3]] [:velocities [:array 43 p/Vec3]]])

(az/defstruct Sample {:layout :extern} [[:bodies [:array 3 Body]]])

(az/defn initial
  :- Body
  [[rigid p/State] [config p/Config]]
  (let [^:var body (mem/zeroes (az/type Body))]
    (dotimes [i particle-count]
      (let [offset (p/scale (az/index mesh/points i) (az/field config radius))]
        (az/set-many!
          (az/index (az/field body positions) i)
          (p/add (az/field rigid position)
                 offset)
          (az/index (az/field body velocities) i)
          (p/add (az/field rigid velocity)
                 (p/cross (az/field rigid omega) offset)))))
    body))

(az/defn inverse-mass
  :- :f64
  [[index :u32] [config p/Config]]
  (/ 1.0 (* (az/field config mass) (az/index mesh/mass-fractions index))))

(az/defn center
  :- p/Vec3
  [[body Body]]
  (let [^:var result (p/v 0.0 0.0 0.0)]
    (dotimes [i particle-count]
      (set! result
            (p/add result
                   (p/scale (az/index (az/field body positions) i)
                            (az/index mesh/mass-fractions i)))))
    result))

(az/defn velocity
  :- p/Vec3
  [[body Body]]
  (let [^:var result (p/v 0.0 0.0 0.0)]
    (dotimes [i particle-count]
      (set! result
            (p/add result
                   (p/scale (az/index (az/field body velocities) i)
                            (az/index mesh/mass-fractions i)))))
    result))

(az/defn kinetic
  :- :f64
  [[body Body] [config p/Config]]
  (let [^{:var :f64} result 0.0]
    (dotimes [i particle-count]
      (let [v (az/index (az/field body velocities) i)]
        (set! result
              (+ result
                 (* 0.5 (az/field config mass) (az/index mesh/mass-fractions i) (p/dot v v))))))
    result))

(az/defn signed-volume
  :- :f64
  [[a p/Vec3] [b p/Vec3] [c p/Vec3] [d p/Vec3]]
  (/ (p/dot (p/add b (p/scale a -1.0))
            (p/cross (p/add c (p/scale a -1.0)) (p/add d (p/scale a -1.0))))
     6.0))

(az/defn volume
  :- :f64
  [[body Body]]
  (let [^{:var :f64} result 0.0]
    (dotimes [i face-count]
      (let [face (az/index mesh/faces i)]
        (set! result
              (+ result
                 (signed-volume (az/index (az/field body positions) 0)
                                (az/index (az/field body positions) (az/index face 0))
                                (az/index (az/field body positions) (az/index face 1))
                                (az/index (az/field body positions) (az/index face 2)))))))
    result))

(az/defn edge-stiffness
  :- :f64
  [[edge :usize] [config p/Config] [modulus :f64]]
  (/ (* modulus mesh/unit-volume (az/field config radius))
     (* mesh/unit-edge-length (az/index mesh/rest-lengths edge))))

(az/defn solve-edge!
  :- :void
  [[body [:* Body]] [edge :usize] [config p/Config] [modulus :f64] [dt :f64] [lambda [:* :f64]]]
  (let [indices (az/index mesh/edges edge)
        a (az/index indices 0)
        b (az/index indices 1)
        pa (az/index (az/field (az/deref body) positions) a)
        pb (az/index (az/field (az/deref body) positions) b)
        delta (p/add pb (p/scale pa -1.0))
        length (p/length delta)]
    (when (< length 1.0e-12) (ak/return))
    (let [normal (p/scale delta (/ 1.0 length))
          wa (inverse-mass a config)
          wb (inverse-mass b config)
          compliance (/ 1.0 (* (edge-stiffness edge config modulus) dt dt))
          constraint (- length (* (az/field config radius) (az/index mesh/rest-lengths edge)))
          increment (/ (- (- constraint) (* compliance (az/deref lambda)))
                       (+ wa wb compliance))]
      (az/set-many!
        (az/deref lambda) (+ (az/deref lambda) increment)
        (az/index (az/field (az/deref body) positions) a)
        (p/add pa (p/scale normal (* (- wa) increment)))
        (az/index (az/field (az/deref body) positions) b)
        (p/add pb (p/scale normal (* wb increment)))))))

(az/defn solve-volume!
  :- :void
  [[body [:* Body]] [index :usize] [config p/Config] [modulus :f64] [dt :f64]
   [lambda [:* :f64]]]
  (let [face (az/index mesh/faces index)
        indices (az/array-init [:array 4 :u32]
                               [0 (az/index face 0) (az/index face 1) (az/index face 2)])
        a (az/index (az/field (az/deref body) positions) 0)
        b (az/index (az/field (az/deref body) positions) (az/index face 0))
        c (az/index (az/field (az/deref body) positions) (az/index face 1))
        d (az/index (az/field (az/deref body) positions) (az/index face 2))
        radius (az/field config radius)
        rest (* (az/index mesh/rest-volumes index) radius radius radius)
        gb (p/scale (p/cross (p/add c (p/scale a -1.0)) (p/add d (p/scale a -1.0)))
                    (/ 1.0 (* 6.0 rest)))
        gc (p/scale (p/cross (p/add d (p/scale a -1.0)) (p/add b (p/scale a -1.0)))
                    (/ 1.0 (* 6.0 rest)))
        gd (p/scale (p/cross (p/add b (p/scale a -1.0)) (p/add c (p/scale a -1.0)))
                    (/ 1.0 (* 6.0 rest)))
        ga (p/scale (p/add gb (p/add gc gd)) -1.0)
        gradients (az/array-init [:array 4 p/Vec3] [ga gb gc gd])
        constraint (- (/ (signed-volume a b c d) rest) 1.0)
        compliance (/ 1.0 (* 20.0 modulus rest dt dt))
        ^{:var :f64} denominator compliance]
    (dotimes [j 4]
      (let [gradient (az/index gradients j)]
        (set! denominator
              (+ denominator
                 (* (inverse-mass (az/index indices j) config) (p/dot gradient gradient))))))
    (let [increment (/ (- (- constraint) (* compliance (az/deref lambda))) denominator)]
      (set! (az/deref lambda) (+ (az/deref lambda) increment))
      (dotimes [j 4]
        (let [particle (az/index indices j)
              old (az/index (az/field (az/deref body) positions) particle)]
          (set! (az/index (az/field (az/deref body) positions) particle)
                (p/add old
                       (p/scale (az/index gradients j)
                                (* (inverse-mass particle config) increment)))))))))

(az/defn project-ground!
  :- :void
  [[body [:* Body]]]
  (dotimes [i particle-count]
    (let [position (ak/& (az/index (az/field (az/deref body) positions) i))]
      (set! (az/field (az/deref position) y) (ak/max 0.0 (az/field (az/deref position) y))))))

(az/defn project-vertex!
  "Vertex/convex-surface contact; equal mass-weighted position reactions."
  :- :void
  [[a [:* Body]] [b [:* Body]] [vertex :u32] [config p/Config]]
  (let [point (az/index (az/field (az/deref a) positions) vertex)
        ^{:var :f64} closest -1.0e30
        ^{:var :usize} closest-face 0
        ^:var normal (p/v 0.0 1.0 0.0)]
    (dotimes [i face-count]
      (let [face (az/index mesh/faces i)
            x (az/index (az/field (az/deref b) positions) (az/index face 0))
            y (az/index (az/field (az/deref b) positions) (az/index face 1))
            z (az/index (az/field (az/deref b) positions) (az/index face 2))
            cross (p/cross (p/add y (p/scale x -1.0)) (p/add z (p/scale x -1.0)))
            length (p/length cross)]
        (when (< length 1.0e-12) (ak/return))
        (let [n (p/scale cross (/ 1.0 length))
              distance (p/dot n (p/add point (p/scale x -1.0)))]
          (when (> distance -1.0e-7) (ak/return))
          (when (> distance closest)
            (az/set-many!
              closest distance
              closest-face i
              normal n)))))
    (let [face (az/index mesh/faces closest-face)
          x (az/index (az/field (az/deref b) positions) (az/index face 0))
          y (az/index (az/field (az/deref b) positions) (az/index face 1))
          z (az/index (az/field (az/deref b) positions) (az/index face 2))
          ab (p/add y (p/scale x -1.0))
          ac (p/add z (p/scale x -1.0))
          ap (p/add (p/add point (p/scale normal (- closest))) (p/scale x -1.0))
          d00 (p/dot ab ab)
          d01 (p/dot ab ac)
          d11 (p/dot ac ac)
          d20 (p/dot ap ab)
          d21 (p/dot ap ac)
          determinant (ak/max 1.0e-15 (- (* d00 d11) (* d01 d01)))
          weight-y (ak/max 0.0 (ak/min 1.0 (/ (- (* d11 d20) (* d01 d21)) determinant)))
          weight-z (ak/max 0.0 (ak/min 1.0 (/ (- (* d00 d21) (* d01 d20)) determinant)))
          weight-x (ak/max 0.0 (- 1.0 weight-y weight-z))
          total (+ weight-x weight-y weight-z)
          weights (az/array-init [:array 3 :f64]
                                 [(/ weight-x total) (/ weight-y total) (/ weight-z total)])
          wa (inverse-mass vertex config)
          ^{:var :f64} denominator wa]
      (dotimes [i 3]
        (set! denominator
              (+ denominator
                 (* (inverse-mass (az/index face i) config)
                    (az/index weights i)
                    (az/index weights i)))))
      (let [increment (/ (- closest) denominator)]
        (set! (az/index (az/field (az/deref a) positions) vertex)
              (p/add point (p/scale normal (* wa increment))))
        (dotimes [i 3]
          (let [particle (az/index face i)]
            (set! (az/index (az/field (az/deref b) positions) particle)
                  (p/add (az/index (az/field (az/deref b) positions) particle)
                         (p/scale normal
                                  (* (- increment)
                                     (az/index weights i)
                                     (inverse-mass particle config)))))))))))

(az/defn bounds-overlap?
  :- :bool
  [[a Body] [b Body]]
  (let [ca (center a)
        cb (center b)
        ^{:var :f64} ra 0.0
        ^{:var :f64} rb 0.0]
    (dotimes [i particle-count]
      (az/set-many!
        ra (ak/max ra (p/length (p/add (az/index (az/field a positions) i) (p/scale ca -1.0))))
        rb
        (ak/max rb
                (p/length (p/add (az/index (az/field b positions) i) (p/scale cb -1.0))))))
    (<= (p/length (p/add cb (p/scale ca -1.0))) (+ ra rb))))

(az/defn advance
  "Four substeps, six XPBD iterations, persistent multipliers within each substep."
  :- Sample
  [[input Sample] [config p/Config] [active :u32] [modulus :f64] [dt :f64]]
  (let [^:var result input
        h (/ dt 4.0)]
    (dotimes [_ 4]
      (let [previous result
            ^:var edge-lambda (mem/zeroes (az/type [:array 3 [:array 162 :f64]]))
            ^:var volume-lambda (mem/zeroes (az/type [:array 3 [:array 80 :f64]]))]
        (dotimes [body-index active]
          (let [body (ak/& (az/index (az/field result bodies) body-index))]
            (dotimes [i particle-count]
              (let [old (az/index (az/field (az/deref body) positions) i)
                    particle-velocity (az/index (az/field (az/deref body) velocities) i)]
                (set! (az/index (az/field (az/deref body) positions) i)
                      (p/add old
                             (p/add (p/scale particle-velocity h)
                                    (p/v 0.0 (* (- (az/field config gravity)) h h) 0.0))))))))
        (dotimes [_ 6]
          (dotimes [body-index active]
            (let [body (ak/& (az/index (az/field result bodies) body-index))]
              (dotimes [edge edge-count]
                (solve-edge! body
                             edge
                             config
                             modulus
                             h
                             (ak/& (az/index (az/index edge-lambda body-index) edge))))
              (dotimes [face face-count]
                (solve-volume! body
                               face
                               config
                               modulus
                               h
                               (ak/& (az/index (az/index volume-lambda body-index) face))))
              (project-ground! body)))
          (dotimes [a active]
            (dotimes [b active]
              (when (< a b)
                (let [pa (ak/& (az/index (az/field result bodies) a))
                      pb (ak/& (az/index (az/field result bodies) b))]
                  (when (bounds-overlap? (az/deref pa) (az/deref pb))
                    (dotimes [i 42]
                      (project-vertex! pa pb (ak/intCast (+ i 1)) config)
                      (project-vertex! pb pa (ak/intCast (+ i 1)) config))))))))
        (dotimes [body-index active]
          (let [body (ak/& (az/index (az/field result bodies) body-index))]
            (project-ground! body)
            (dotimes [i particle-count]
              (let [position (az/index (az/field (az/deref body) positions) i)
                    old (az/index (az/field (az/index (az/field previous bodies) body-index)
                                            positions)
                                  i)
                    ^:var v (p/scale (p/add position (p/scale old -1.0)) (/ 1.0 h))]
                (when (< (az/field position y) 1.0e-7)
                  (let [tangent (p/v (az/field v x) 0.0 (az/field v z))
                        speed (p/length tangent)
                        previous-vy (az/field (az/index (az/field (az/index (az/field previous
                                                                                      bodies)
                                                                            body-index)
                                                                  velocities)
                                                        i)
                                              y)
                        budget (* (az/field config friction)
                                  (ak/max 0.0 (- (az/field v y) previous-vy)))
                        factor (if (> speed 1.0e-12) (ak/max 0.0 (- 1.0 (/ budget speed))) 0.0)]
                    (set! v
                          (p/v (* factor (az/field v x))
                               (ak/max 0.0 (az/field v y))
                               (* factor (az/field v z))))))
                (set! (az/index (az/field (az/deref body) velocities) i) v)))
            (let [mean (velocity (az/deref body))
                  decay (ak/exp (* -2.0 h))]
              (dotimes [i particle-count]
                (let [v (az/index (az/field (az/deref body) velocities) i)]
                  (set! (az/index (az/field (az/deref body) velocities) i)
                        (p/add mean (p/scale (p/add v (p/scale mean -1.0)) decay))))))))))
    result))

(az/defn elastic-energy
  :- :f64
  [[body Body] [config p/Config] [modulus :f64]]
  (let [^{:var :f64} result 0.0
        radius (az/field config radius)]
    (dotimes [i edge-count]
      (let [edge (az/index mesh/edges i)
            delta (p/add (az/index (az/field body positions) (az/index edge 1))
                         (p/scale (az/index (az/field body positions) (az/index edge 0)) -1.0))
            extension (- (p/length delta) (* radius (az/index mesh/rest-lengths i)))]
        (set! result (+ result (* 0.5 (edge-stiffness i config modulus) extension extension)))))
    (dotimes [i face-count]
      (let [face (az/index mesh/faces i)
            rest (* (az/index mesh/rest-volumes i) radius radius radius)
            current (signed-volume (az/index (az/field body positions) 0)
                                   (az/index (az/field body positions) (az/index face 0))
                                   (az/index (az/field body positions) (az/index face 1))
                                   (az/index (az/field body positions) (az/index face 2)))
            strain (- (/ current rest) 1.0)]
        (set! result (+ result (* 10.0 modulus rest strain strain)))))
    result))

(az/defn energy
  :- :f64
  [[body Body] [config p/Config] [modulus :f64]]
  (+ (kinetic body config)
     (elastic-energy body config modulus)
     (* (az/field config mass) (az/field config gravity) (az/field (center body) y))))

(az/defn height
  :- :f64
  [[body Body]]
  (let [^{:var :f64} low 1.0e30
        ^{:var :f64} high -1.0e30]
    (dotimes [i particle-count]
      (let [y (az/field (az/index (az/field body positions) i) y)]
        (az/set-many!
          low (ak/min low y)
          high (ak/max high y))))
    (- high low)))

(az/defn clearance
  :- :f64
  [[body Body]]
  (let [^{:var :f64} result 1.0e30]
    (dotimes [i particle-count]
      (set! result (ak/min result (az/field (az/index (az/field body positions) i) y))))
    result))
