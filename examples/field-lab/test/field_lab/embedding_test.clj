(ns field-lab.embedding-test
  (:require [clojure.test :refer [deftest is testing]]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.physics :as p]
            [field-lab.mesh-cache :as cache]
            [field-lab.hyperelastic :as matrix]
            [field-lab.embedding :as embedding]
            [field-lab.nonlinear-job :as job]))

(az/defn fixture! [:* cache/Cache]
  [[cells :usize]]
  (let [source (cache/create! 5 cells 1 2 (p/defaults) 1000.0 0.3)
        points (az/array-init [(p/v 0.0 0.0 0.0) (p/v 1.0 0.0 0.0) (p/v 0.0 1.0 0.0)
                  (p/v 0.0 0.0 1.0) (p/v 0.0 0.0 -1.0)] [:array 5 p/Vec3])]
    (dotimes [node 5]
      (let [point (az/index points node)
            x (az/field point x)
            y (az/field point y)
            z (az/field point z)]
        (az/set-many!
          (az/index (az/field source reference) node) point
          (az/index (az/field source positions) node) point
          (az/index (az/field source positions) (+ 5 node))
          (p/v (+ 2.0 (* 0.2 x) (* -1.3 y) (* 0.4 z))
               (+ -1.0 (* 1.1 x) (* 0.3 y))
               (+ 3.0 (* 0.1 y) (* 0.7 z))))))
    (set! (az/index (az/field source cells) 0) (az/array-init [0 1 2 3] [:array 4 :u32]))
    (when (ak/== cells 2)
      (set! (az/index (az/field source cells) 1) (az/array-init [0 2 1 4] [:array 4 :u32])))
    (set! (az/field source count) 2)
    source))

(az/defn rendered-point p/Vec3
  [[render [:* embedding/Render]] [index :usize]]
  (az/index (az/field render positions) index))

(az/defn rendered-normal p/Vec3
  [[render [:* embedding/Render]] [index :usize]]
  (az/index (az/field render normals) index))

(az/defn vertex-gradient matrix/Matrix
  [[render [:* embedding/Render]] [index :usize]]
  (az/index (az/field render gradients) index))

(az/defn weights [:array 4 :f64]
  [[render [:* embedding/Render]] [index :usize]]
  (az/index (az/field render cell-weights) index))

(az/defn alternate-parent! :void
  [[render [:* embedding/Render]]]
  ;; The same shared-face material point expressed in the other tetrahedron.
  (set! (az/index (az/field render bindings) 0)
        (embedding/Binding {:cell 1 :weights (az/array-init [0.5 0.3 0.2 0.0] [:array 4 :f64]) :valid true})))

(az/defn bend! :void
  [[source [:* cache/Cache]]]
  (az/set-many!
    (az/index (az/field source positions) 8) (p/v 0.2 0.1 1.2)
    (az/index (az/field source positions) 9) (p/v -0.1 0.2 -0.8)))

(def points [{:x 0.2 :y 0.3 :z 0.0}
             {:x 0.1 :y 0.1 :z 0.2}
             {:x 0.3 :y 0.1 :z 0.1}])

(defn near-vector? [expected actual tolerance]
  (every? #(< (abs (- (double (expected %)) (double (actual %)))) tolerance) [:x :y :z]))

(defn affine [{:keys [x y z]}]
  {:x (+ 2.0 (* 0.2 x) (* -1.3 y) (* 0.4 z))
   :y (+ -1.0 (* 1.1 x) (* 0.3 y))
   :z (+ 3.0 (* 0.1 y) (* 0.7 z))})

(defn with-render [cells action]
  (let [source (fixture! cells)
        render (embedding/create! source 3 1)]
    (try
      (doseq [[index point] (map-indexed vector points)]
        (assert (embedding/bind! render source index point)))
      (embedding/set-face! render 0 0 1 2)
      (action source render)
      (finally
        (embedding/destroy! render)
        (cache/destroy! source)))))

(deftest identity-affine-and-sparse-neighbors
  (doseq [cells [1 2]]
    (with-render cells
      (fn [source render]
        (doseq [phong [false true]
                tick [0 1]]
          (is (true? (embedding/update! render source tick phong)))
          (doseq [[index point] (map-indexed vector points)]
            (is (near-vector? (if (zero? tick) point (affine point))
                              (az/value (rendered-point render index)) 2.0e-14))))
        (doseq [index (if (= cells 1) (range 4) (range 5))]
          (let [gradient (az/value (vertex-gradient render index))]
            (is (near-vector? {:x 0.2 :y 1.1 :z 0.0} (:c0 gradient) 2.0e-14))
            (is (near-vector? {:x -1.3 :y 0.3 :z 0.1} (:c1 gradient) 2.0e-14))
            (is (near-vector? {:x 0.4 :y 0.0 :z 0.7} (:c2 gradient) 2.0e-14))))))))

(deftest continuous-shared-face-and-normalized-reconstruction
  (with-render 2
    (fn [source render]
      (let [[a b] (mapv #(az/value (weights render %)) [0 1])]
        (doseq [sum [(+ (a 0) (b 0)) (+ (a 1) (b 2)) (+ (a 2) (b 1)) (a 3) (b 3)]]
          (is (< (abs (- 1.0 sum)) 2.0e-14))))
      (bend! source)
      (is (embedding/update! render source 1 true))
      (let [first-side (az/value (rendered-point render 0))]
        (alternate-parent! render)
        (is (embedding/update! render source 1 true))
        (is (near-vector? first-side (az/value (rendered-point render 0)) 2.0e-14)))
      (let [normal (az/value (rendered-normal render 0))]
        (is (every? #(Double/isFinite (double %)) (vals normal)))
        (is (> (reduce + (map #(* % %) (vals normal))) 1.0e-8))))))

(deftest invalid-binding-and-frame-rejected
  (with-render 2
    (fn [source render]
      (is (false? (embedding/update! render source 2 true)))
      (doseq [point [{:x 0.5 :y 0.5 :z 0.5}
                    {:x Double/NaN :y 0.0 :z 0.0}
                    {:x Double/POSITIVE_INFINITY :y 0.0 :z 0.0}]]
        (is (false? (embedding/bind! render source 0 point)))
        (is (false? (embedding/update! render source 0 true))))
      (is (embedding/bind! render source 0 (points 0)))
      (is (embedding/update! render source 0 true)))))

(az/defstruct SphereInspection {:layout :extern}
  [[:created :bool] [:completed :bool] [:linear-frames :u32] [:minimum-phong-height :f64] [:points :usize] [:faces :usize]
   [:minimum-height :f64] [:maximum-correction :f64] [:inset :f64]
   [:minimum-outward-normal :f64]])

(az/defn inspect-sphere! SphereInspection
  [[source [:* cache/Cache]]]
  (let [owned (embedding/inscribed-sphere! source)
        ^:var result (SphereInspection {:created (ak/!= owned null) :completed false
                                       :linear-frames 0 :minimum-phong-height 1.0e30
                                       :points 0 :faces 0 :minimum-height 1.0e30
                                       :maximum-correction 0.0 :inset 0.0
                                       :minimum-outward-normal 1.0e30})]
    (when (ak/== owned null) (ak/return result))
    (let [render (az/unwrap owned)]
      (defer (embedding/destroy! render))
      (az/set-many!
        (az/field result points) (az/field (az/field render reference) len)
        (az/field result faces) (az/field (az/field render faces) len)
        (az/field result inset) (az/field render reference-inset))
      (dotimes [tick (az/field source count)]
        (when (ak/! (embedding/update! render source (ak/intCast tick) true)) (ak/return result))
        (set! (az/field result minimum-phong-height)
              (ak/min (az/field result minimum-phong-height) (az/field render minimum-height)))
        (let [method (embedding/update-for-floor! render source (ak/intCast tick) true)]
          (when (ak/== method 0) (ak/return result))
          (when (ak/== method 2) (ak/+= (az/field result linear-frames) 1)))
        (az/set-many!
          (az/field result minimum-height) (ak/min (az/field result minimum-height) (az/field render minimum-height))
          (az/field result maximum-correction) (ak/max (az/field result maximum-correction) (az/field render maximum-correction)))
        (when (ak/== tick 0)
          (let [center (az/field (az/field (cache/frame-info source 0) observation) center)]
            (dotimes [point (az/field (az/field render reference) len)]
              (let [offset (p/add (az/index (az/field render positions) point) (p/scale center -1.0))
                    normal (az/index (az/field render normals) point)]
                (set! (az/field result minimum-outward-normal)
                      (ak/min (az/field result minimum-outward-normal) (p/dot offset normal))))))))
      (set! (az/field result completed) true))
    result))

(deftest non-spherical-boundary-is-not-replaced
  (let [source (fixture! 2)]
    (try
      (cache/set-face! source 0 0 2 1)
      (is (false? (:created (az/value (inspect-sphere! source)))))
      (finally (cache/destroy! source)))))

(deftest detailed-sphere-contained-and-outward
  (let [{:keys [cache]} (job/bake-cache! {:refinement 1 :seconds (/ 1.0 240.0)
                                        :maximum-step 0.001 :config {:gravity 0.0}})]
    (try
      (let [inspection (az/value (inspect-sphere! cache))]
        (is (:created inspection))
        (is (:completed inspection))
        (is (= 1106 (:points inspection)))
        (is (= 2208 (:faces inspection)))
        (is (pos? (:inset inspection)))
        (is (< (:inset inspection) 0.02))
        (is (> (:minimum-height inspection) 0.5))
        (is (< (:maximum-correction inspection) 1.0e-12))
        (is (pos? (:minimum-outward-normal inspection))))
      (finally (cache/destroy! cache)))))

(az/defn shear-above-floor! :void
  [[source [:* cache/Cache]]]
  (dotimes [node 5]
    (set! (az/index (az/field source positions) (+ 5 node))
          (az/index (az/field source reference) node)))
  (set! (az/index (az/field source positions) 8) (p/v 0.0 0.5 1.0)))

(deftest floor-overshoot-uses-linear-transfer-without-clamping
  (with-render 2
    (fn [source render]
      (shear-above-floor! source)
      (is (embedding/bind! render source 0 {:x 0.1 :y 0.0001 :z -0.2}))
      (is (embedding/update! render source 1 true))
      (is (neg? (:y (az/value (rendered-point render 0)))))
      (is (= 2 (embedding/update-for-floor! render source 1 true)))
      (is (near-vector? {:x 0.1 :y 0.0001 :z -0.2}
                        (az/value (rendered-point render 0)) 2.0e-14))
      (is (= 1 (embedding/update-for-floor! render source 1 false)))
      (is (neg? (:y (az/value (rendered-point render 0))))))))
