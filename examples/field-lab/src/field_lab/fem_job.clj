(ns field-lab.fem-job
  "Clojure-authored meshes, boundary conditions and reproducible native FEM jobs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [aguafria.zig :as az]
            [field-lab.fem :as fem]
            [field-lab.fem-export :as export]))

(defn subtract
  [a b]
  (mapv - a b))

(defn dot
  [a b]
  (reduce + (map * a b)))

(defn cross [[ax ay az] [bx by bz]]
  [(- (* ay bz) (* az by))
   (- (* az bx) (* ax bz))
   (- (* ax by) (* ay bx))])

(defn scale
  [factor vector]
  (mapv #(* factor %) vector))

(defn tetrahedron
  "Physical volume and gradients of the four linear shape functions."
  [points [a b c d]]
  (let [origin (points a)
        ab (subtract (points b) origin)
        ac (subtract (points c) origin)
        ad (subtract (points d) origin)
        determinant (dot ab (cross ac ad))
        gb (scale (/ 1.0 determinant) (cross ac ad))
        gc (scale (/ 1.0 determinant) (cross ad ab))
        gd (scale (/ 1.0 determinant) (cross ab ac))]
    {:volume (/ (abs determinant) 6.0)
     :gradients [(mapv #(- (+ %1 %2 %3)) gb gc gd) gb gc gd]}))

(defn box-mesh
  "Conforming six-tetrahedra subdivision per Cartesian cell. Lengths are metres."
  [[nx ny nz] [lx ly lz]]
  (when-not (and (every? #(and (integer? %) (pos? %)) [nx ny nz])
                 (every? #(and (number? %) (Double/isFinite (double %)) (pos? %)) [lx ly lz]))
    (throw (ex-info "Positive integer subdivisions and finite positive lengths required" {})))
  (let [node (fn [i j k] (+ i (* (inc nx) (+ j (* (inc ny) k)))))
        points (vec (for [k (range (inc nz)) j (range (inc ny)) i (range (inc nx))]
                      [(* lx (/ (double i) nx)) (* ly (/ (double j) ny)) (* lz (/ (double k) nz))]))
        cells (vec (mapcat
                    (fn [[i j k]]
                      (let [a (node i j k) b (node (inc i) j k)
                            c (node (inc i) (inc j) k) d (node i (inc j) k)
                            e (node i j (inc k)) f (node (inc i) j (inc k))
                            g (node (inc i) (inc j) (inc k)) h (node i (inc j) (inc k))]
                        [[a b c g] [a c d g] [a d h g]
                         [a h e g] [a e f g] [a f b g]]))
                    (for [k (range nz) j (range ny) i (range nx)] [i j k])))]
    {:points points :cells cells}))

(defn boundary-faces [{:keys [cells]}]
  (->> cells
       (mapcat (fn [[a b c d]] [[a b c] [a b d] [a c d] [b c d]]))
       (group-by #(vec (sort %)))
       vals
       (filter #(= 1 (count %)))
       (mapv first)))

(defn prescribed
  "Create [node axis displacement-metres] conditions from a point predicate."
  [{:keys [points]} selected displacement]
  (vec (for [[node point] (map-indexed vector points)
             :when (selected point)
             [axis value] (map-indexed vector (displacement point))]
         [node axis (double value)])))

(defn body-load
  "Consistent nodal load for a constant force density in N/m³."
  [{:keys [points cells]} force-density]
  (vec (for [cell cells
             :let [weight (/ (:volume (tetrahedron points cell)) 4.0)]
             node cell
             [axis value] (map-indexed vector force-density)
             :when (not (zero? value))]
         [node axis (* weight value)])))

(defn traction-load
  "Consistent nodal load for constant traction in Pa on selected boundary faces."
  [{:keys [points] :as mesh} selected traction]
  (vec (for [[a b c :as face] (boundary-faces mesh)
             :when (every? #(selected (points %)) face)
             :let [normal (cross (subtract (points b) (points a))
                                (subtract (points c) (points a)))
                   weight (/ (Math/sqrt (dot normal normal)) 6.0)]
             node face
             [axis value] (map-indexed vector traction)
             :when (not (zero? value))]
         [node axis (* weight value)])))

(defn- matrix-rank [rows]
  (loop [matrix (mapv vec rows) pivot 0 column 0]
    (if (or (= column 6) (= pivot (count matrix)))
      pivot
      (let [candidate (first (sort-by #(abs (double (get-in matrix [% column]))) >
                                      (range pivot (count matrix))))]
        (if (< (abs (double (get-in matrix [candidate column]))) 1.0e-10)
          (recur matrix pivot (inc column))
          (let [swapped (assoc matrix pivot (matrix candidate) candidate (matrix pivot))
                row (scale (/ 1.0 (get-in swapped [pivot column])) (swapped pivot))
                reduced (mapv (fn [index old]
                                (if (= index pivot) row
                                    (subtract old (scale (old column) row))))
                              (range) swapped)]
            (recur reduced (inc pivot) (inc column))))))))

(defn- finite? [value]
  (and (number? value) (Double/isFinite (double value))))

(defn validate!
  [{:keys [mesh material constraints loads relative-tolerance absolute-tolerance max-iterations]}
   & {:keys [require-support?] :or {require-support? true}}]
  (let [{:keys [points cells]} mesh
        {:keys [young-Pa poisson-ratio]} material
        node-count (count points)]
    (when-not (and (vector? points) (<= 4 node-count 1000000)
                   (every? #(and (= 3 (count %)) (every? finite? %)) points)
                   (vector? cells) (seq cells)
                   (every? #(and (= 4 (count %)) (= 4 (count (set %)))
                                 (every? (fn [index] (and (integer? index) (<= 0 index) (< index node-count))) %)) cells)
                   (finite? young-Pa) (pos? young-Pa)
                   (finite? poisson-ratio) (< -1.0 poisson-ratio 0.5))
      (throw (ex-info "Invalid tetrahedral mesh or isotropic elastic material" {})))
    (doseq [entries [constraints loads]
            entry entries]
      (let [[node axis value] entry]
        (when-not (and (= 3 (count entry)) (integer? node) (<= 0 node) (< node node-count)
                       (#{0 1 2} axis) (finite? value))
          (throw (ex-info "Expected [node axis finite-SI-value]" {:entry entry})))))
    (when-not (and (finite? relative-tolerance) (< 0.0 relative-tolerance 1.0)
                   (finite? absolute-tolerance) (pos? absolute-tolerance)
                   (integer? max-iterations) (pos? max-iterations))
      (throw (ex-info "Invalid solver tolerance or iteration limit" {})))
    (doseq [[dof values] (group-by #(subvec (vec %) 0 2) constraints)]
      (when (> (count (set (map last values))) 1)
        (throw (ex-info "Conflicting prescribed displacements" {:dof dof}))))
    ;; Require one connected body with every point participating in an element.
    ;; Separate bodies must have separate jobs and their own support conditions.
    (let [neighbors (reduce (fn [graph cell]
                             (reduce #(update %1 %2 (fnil into #{}) cell) graph cell)) {} cells)
          reached (loop [visited #{} frontier #{0}]
                    (if (empty? frontier) visited
                        (let [next-visited (into visited frontier)]
                          (recur next-visited
                                 (set/difference (set (mapcat neighbors frontier)) next-visited)))))]
      (when (not= node-count (count reached))
        (throw (ex-info "Mesh has disconnected bodies or unused nodes" {}))))
    (let [origin (first points)
          extent (apply max (map #(Math/sqrt (dot % %)) (map #(subtract % origin) points)))
          rows (map (fn [[node axis _]]
                      (let [[x y z] (scale (/ 1.0 (max extent 1.0e-100)) (subtract (points node) origin))]
                        (case axis
                          0 [1.0 0.0 0.0 0.0 z (- y)]
                          1 [0.0 1.0 0.0 (- z) 0.0 x]
                          2 [0.0 0.0 1.0 y (- x) 0.0]))) constraints)]
      (when (and require-support? (< (matrix-rank rows) 6))
        (throw (ex-info "Boundary conditions leave a rigid translation or rotation unconstrained" {}))))))

(defn- solver-version []
  (into (sorted-map)
        (for [module ['field-lab.fem 'field-lab.physics]
              :let [info (az/module-info module)]]
          [(str module)
           {:zig-version (:zig-version info)
            :declarations (->> (:definitions info)
                               (map #(select-keys % [:logical-id :implementation-fingerprint :schema-fingerprint]))
                               (sort-by (comp pr-str :logical-id))
                               vec)}])))

(defn solve!
  "Solve one immutable job description; return owned Clojure result data.
  Failed convergence is explicit. Native buffers are always released."
  [description]
  (let [{:keys [mesh material constraints loads relative-tolerance absolute-tolerance max-iterations]
         :as job} (merge {:constraints [] :loads [] :relative-tolerance 1.0e-9
                          :absolute-tolerance 1.0e-10 :max-iterations 10000} description)
        {:keys [points cells]} mesh]
    (validate! job)
    (az/await! 'field-lab.fem)
    (let [version (solver-version)
          model (fem/create! (count points) (count cells)
                            (double (:young-Pa material)) (double (:poisson-ratio material)))]
      (try
        (doseq [[index [x y z]] (map-indexed vector points)]
          (fem/set-node! model index (double x) (double y) (double z)))
        (doseq [[index [a b c d]] (map-indexed vector cells)]
          (when-not (fem/set-element! model index a b c d)
            (throw (ex-info "Degenerate tetrahedron" {:element index}))))
        (doseq [[node axis value] constraints]
          (fem/constrain! model (+ (* 3 node) axis) (double value)))
        (doseq [[node axis value] loads]
          (fem/load! model (+ (* 3 node) axis) (double value)))
        (let [report (az/value (fem/solve! model relative-tolerance absolute-tolerance max-iterations))
              result
          {:format :field-lab/linear-tet-elasticity-v1
           :solver-version version
           :job job
           :report report
           :displacements-m (mapv (fn [node] (mapv #(fem/displacement model (+ (* 3 node) %)) (range 3)))
                                  (range (count points)))
           :reactions-N (mapv (fn [node] (mapv #(fem/reaction model (+ (* 3 node) %)) (range 3)))
                              (range (count points)))
           :stress-Pa (mapv (fn [element] (mapv #(fem/stress-component model element %) (range 9)))
                            (range (count cells)))}]
          (when (not= version (solver-version))
            (throw (ex-info "Native solver changed during the job; rerun with the new version" {})))
          result)
        (finally (fem/destroy! model))))))

(defn cantilever
  "A 1 × 0.1 × 0.1 m beam, clamped at x=0, loaded by a uniform end traction.
  This is 3D continuum elasticity; beam theory is only a slender-beam reference."
  [refinement]
  (let [mesh (box-mesh [(* 8 refinement) refinement refinement] [1.0 0.1 0.1])]
    {:mesh mesh
     :material {:young-Pa 2.0e9 :poisson-ratio 0.3}
     :constraints (prescribed mesh #(zero? (first %)) (constantly [0.0 0.0 0.0]))
     :loads (traction-load mesh #(= 1.0 (first %)) [0.0 -1000.0 0.0])}))

(defn write-results! [result output]
  (io/make-parents output)
  (spit output (pr-str result))
  (let [summary {:output (.getCanonicalPath (io/file output)) :report (:report result)}
        mesh-path (str output ".vtu")]
    (if (get-in result [:report :converged])
      (assoc summary :vtu (export/write-vtu! result mesh-path))
      (do
        ;; A failed rerun must not leave a previous successful mesh beside its
        ;; new diagnostics, where it could be mistaken for the current result.
        (java.nio.file.Files/deleteIfExists (.toPath (io/file mesh-path)))
        summary))))

(defn -main [& [input output]]
  (let [job (if input (edn/read-string (slurp input)) (cantilever 2))
        result (solve! job)
        target (or output "exports/fem-result.edn")]
    (prn (write-results! result target))
    (shutdown-agents)
    (when-not (get-in result [:report :converged]) (System/exit 1))))
