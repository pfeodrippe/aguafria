(ns pitoco.geometry
  "Pure Clojure tetrahedral source geometry, usable by independent scripting programs.
  Surface vertices belong to the FEM mesh; no display-only surface is generated.")

(defn normalize
  [point]
  (let [length (Math/sqrt (reduce + (map #(* % %) point)))] (mapv #(/ % length) point)))

(defn cross
  [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by)) (- (* az bx) (* ax bz)) (- (* ax by) (* ay bx))])

(defn dot [a b] (reduce + (map * a b)))

(defn make-seed-sphere
  []
  (let [phi (/ (+ 1.0 (Math/sqrt 5.0)) 2.0)
        vertices (atom (mapv normalize
                             [[-1 phi 0] [1 phi 0] [-1 (- phi) 0] [1 (- phi) 0] [0 -1 phi] [0 1 phi]
                              [0 -1 (- phi)] [0 1 (- phi)] [phi 0 -1] [phi 0 1] [(- phi) 0 -1]
                              [(- phi) 0 1]]))
        midpoints (atom {})
        midpoint (fn [a b]
                   (let [key (sort [a b])]
                     (or (@midpoints key)
                         (let [index (count @vertices)]
                           (swap! vertices conj
                                  (normalize (mapv + (@vertices a) (@vertices b))))
                           (swap! midpoints assoc key index)
                           index))))
        faces [[0 11 5] [0 5 1] [0 1 7] [0 7 10] [0 10 11] [1 5 9] [5 11 4] [11 10 2] [10 7 6]
               [7 1 8] [3 9 4] [3 4 2] [3 2 6] [3 6 8] [3 8 9] [4 9 5] [2 4 11] [6 2 10] [8 6 7]
               [9 8 1]]
        triangles (vec (mapcat (fn [[a b c]]
                                 (let [ab (midpoint a b)
                                       bc (midpoint b c)
                                       ca (midpoint c a)]
                                   [[a ab ca] [b bc ab] [c ca bc] [ab bc ca]]))
                               faces))
        triangles (mapv #(mapv inc %) triangles)
        points (into [[0.0 0.0 0.0]] @vertices)
        volumes (mapv (fn [[a b c]] (/ (dot (points a) (cross (points b) (points c))) 6.0))
                      triangles)
        total-volume (reduce + volumes)
        masses (reduce (fn [result [face volume]]
                         (reduce #(update %1 %2 + (/ volume (* 4.0 total-volume)))
                                 result
                                 (cons 0 face)))
                       (vec (repeat (count points) 0.0))
                       (map vector triangles volumes))
        edges (->> (concat (mapcat (fn [[a b c]] [[a b] [b c] [c a]]) triangles)
                           (map #(vector 0 %) (range 1 (count points))))
                   (map #(vec (sort %)))
                   distinct
                   sort
                   vec)
        lengths (mapv (fn [[a b]]
                        (Math/sqrt (reduce +
                                           (map #(let [d (- %1 %2)] (* d d)) (points a) (points b)))))
                      edges)]
    {:points points
     :triangles triangles
     :volumes volumes
     :volume total-volume
     :mass-fractions masses
     :edges edges
     :lengths lengths
     :total-length (reduce + lengths)}))

(defn scale [factor point]
  (mapv (partial * factor) point))

(defn subtract [a b]
  (mapv - a b))

(defn length [point]
  (Math/sqrt (dot point point)))

(defn signed-six-volume [points [a b c d]]
  (dot (subtract (points b) (points a))
       (cross (subtract (points c) (points a)) (subtract (points d) (points a)))))

(defn boundary-faces
  "Deterministic outward-oriented boundary of a conforming tetrahedral mesh."
  [{:keys [points cells]}]
  (->> cells
       (mapcat (fn [[a b c d]] [[[a b c] d] [[a b d] c] [[a c d] b] [[b c d] a]]))
       (group-by #(vec (sort (first %))))
       (sort-by key)
       (keep (fn [[_ entries]]
               (when (= 1 (count entries))
                 (let [[[a b c] opposite] (first entries)
                       normal (cross (subtract (points b) (points a))
                                     (subtract (points c) (points a)))]
                   (if (pos? (dot normal (subtract (points opposite) (points a))))
                     [a c b] [a b c])))))
       vec))

(defn refine
  "Conforming 1-to-8 tetrahedral refinement on the same polyhedral domain.
  Projection is intentionally a separate operation from fixed-domain refinement."
  [{:keys [points cells]}]
  (let [vertices (atom points)
        edges (atom {})
        midpoint (fn [a b]
                   (let [edge (vec (sort [a b]))]
                     (or (@edges edge)
                         (let [index (count @vertices)]
                           (swap! vertices conj (scale 0.5 (mapv + (points a) (points b))))
                           (swap! edges assoc edge index)
                           index))))
        refined
        (vec (mapcat (fn [[a b c d]]
                       (let [ab (midpoint a b) ac (midpoint a c) ad (midpoint a d)
                             bc (midpoint b c) bd (midpoint b d) cd (midpoint c d)]
                         [[a ab ac ad] [ab b bc bd] [ac bc c cd] [ad bd cd d]
                          [ab ac ad cd] [ab ac bc cd] [ab ad bd cd] [ab bc bd cd]]))
                     cells))]
    {:points @vertices :cells refined}))

(defn project-sphere-boundary
  "Project a unit-centered sphere's boundary nodes and reject element inversion.
  This is restricted source construction, not a general curved-CAD mesh repairer."
  [{:keys [points cells] :as mesh}]
  (let [boundary (set (mapcat identity (boundary-faces mesh)))
        projected (mapv (fn [index point] (if (boundary index) (normalize point) point))
                         (range (count points)) points)]
    (doseq [[index cell] (map-indexed vector cells)]
      (let [before (signed-six-volume points cell)
            after (signed-six-volume projected cell)]
        (when-not (and (Double/isFinite after) (> (* before after) 0.0))
          (throw (ex-info "Spherical boundary projection would invert a tetrahedron"
                          {:cell index :before before :after after})))))
    (assoc mesh :points projected)))

(defn metrics
  "Exact volume, centroid and integrated inertia for linear tetrahedra.
  The inertia diagonal is about origin and normalized by total volume (m²).
  Mean-ratio quality is one for an equilateral tetrahedron and approaches zero
  for degenerate elements; orientation is reported separately."
  ([mesh] (metrics mesh [0.0 0.0 0.0]))
  ([{:keys [points cells] :as mesh} origin]
   (let [local (mapv #(subtract % origin) points)
         records
         (mapv (fn [cell]
                 (let [vertices (mapv local cell)
                       determinant (signed-six-volume local cell)
                       volume (/ (abs determinant) 6.0)
                       sum (apply mapv + vertices)
                       second (mapv (fn [axis]
                                      (* (/ volume 20.0)
                                         (+ (* (sum axis) (sum axis))
                                            (reduce + (map #(let [x (% axis)] (* x x)) vertices)))))
                                    (range 3))
                       edge-squares (reduce + (for [a (range 4) b (range (inc a) 4)]
                                                (let [edge (subtract (vertices a) (vertices b))]
                                                  (dot edge edge))))]
                   {:signed-volume (/ determinant 6.0) :volume volume
                    :first (scale (/ volume 4.0) sum)
                    :lumped-inertia
                    (mapv (fn [axis]
                            (* (/ volume 4.0)
                               (reduce + (map #(- (dot % %) (* (% axis) (% axis))) vertices))))
                          (range 3))
                    :inertia [(+ (second 1) (second 2))
                               (+ (second 0) (second 2))
                               (+ (second 0) (second 1))]
                    :quality (/ (* 12.0 (Math/pow (* 3.0 volume) (/ 2.0 3.0))) edge-squares)}))
               cells)
         volume (reduce + (map :volume records))
         centroid (mapv + origin (scale (/ 1.0 volume) (apply mapv + (map :first records))))
         faces (boundary-faces mesh)
         area (reduce + (map (fn [[a b c]]
                                (* 0.5 (length (cross (subtract (points b) (points a))
                                                     (subtract (points c) (points a)))))) faces))]
     {:nodes (count points) :tetrahedra (count cells) :boundary-triangles (count faces)
      :volume volume :surface-area area :centroid centroid
      :minimum-signed-volume (apply min (map :signed-volume records))
      :minimum-mean-ratio (apply min (map :quality records))
      :inertia-per-mass (scale (/ 1.0 volume) (apply mapv + (map :inertia records)))
      :lumped-inertia-per-mass (scale (/ 1.0 volume) (apply mapv + (map :lumped-inertia records)))})))

(defn sphere
  "Tetrahedral solid approximating a sphere with actual boundary refinement.
  :geometry :polyhedron preserves the seed domain for fixed-domain h studies.
  Returned source data and metrics are ordinary Clojure/EDN values."
  [{:keys [radius center refinement geometry]
    :or {radius 1.0 center [0.0 0.0 0.0] refinement 1 geometry :sphere}}]
  (when-not (and (number? radius) (Double/isFinite (double radius)) (pos? radius)
                 (vector? center) (= 3 (count center))
                 (every? #(and (number? %) (Double/isFinite (double %))) center)
                 (integer? refinement) (<= 0 refinement 3) (#{:sphere :polyhedron} geometry))
    (throw (ex-info "Use finite sphere dimensions, refinement 0–3 and a known geometry mode" {})))
  (let [seed (make-seed-sphere)
        initial {:points (:points seed) :cells (mapv #(into [0] %) (:triangles seed))}
        mesh (nth (iterate (if (= geometry :sphere)
                             (comp project-sphere-boundary refine) refine) initial) refinement)
        mesh (update mesh :cells
                     (fn [cells]
                       (mapv (fn [[a b c d :as cell]]
                               (if (pos? (signed-six-volume (:points mesh) cell)) cell [a c b d])) cells)))
        mesh (update mesh :points #(mapv (fn [point] (mapv + center (scale radius point))) %))
        measures (metrics mesh center)
        analytic-volume (* (/ 4.0 3.0) Math/PI radius radius radius)]
    (when-not (and (pos? (:minimum-signed-volume measures))
                   (> (:minimum-mean-ratio measures) 0.01)
                   (Double/isFinite (:volume measures)))
      (throw (ex-info "Sphere mesh failed the element-quality check" measures)))
    (assoc mesh
           :source {:shape :solid-sphere :geometry geometry :radius-m radius
                    :center-m center :refinement refinement :version 1}
           :metrics (assoc measures :sphere-volume-relative-error
                           (- 1.0 (/ (:volume measures) analytic-volume))))))
