(ns field-lab.job
  "Offline numerical jobs. No window, rendering loop, or display-clock dependency."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [aguafria.zig :as az]
            [aguafria.keyword :as ak]
            [field-lab.contacts :as contacts]
            [field-lab.physics :as p]
            [field-lab.scene :as scene]
            [field-lab.soft-body :as soft]
            [field-lab.soft-mesh :as mesh]))

(az/defn cached-summary
  :- contacts/Sample
  [[tick :u32]]
  (az/index scene/history tick))

(az/defn cached-particles
  :- soft/Sample
  [[tick :u32]]
  (az/index scene/soft-history tick))

(defn run-job!
  "Compute and stream a self-describing EDN cache. Run one job per process."
  [{:keys [bodies seconds stiffness model output config]
    :or {bodies 1 seconds 6.0 stiffness 10000.0 model :xpbd
         output "exports/cache.edn" config {}}}]
  (when-not (and (#{1 3} bodies) (#{:rigid :xpbd :fem} model)
                 (number? seconds) (<= 0.0 seconds 60.0)
                 (number? stiffness) (<= 1000.0 stiffness 100000.0))
    (throw (ex-info "Expected 1 or 3 bodies, :rigid, :xpbd or :fem, 0–60 seconds, and stiffness 1000–100000 Pa"
                    {:bodies bodies :seconds seconds :stiffness stiffness :model model})))
  (let [defaults (az/value (p/defaults))
        settings (merge defaults config)
        bounds {:radius [0.1 1.0] :mass [0.05 20.0] :height [0.1 8.0]
                :gravity [0.0 20.0] :restitution [0.0 1.0] :friction [0.0 1.0]
                :rolling [0.0 0.2] :vx [-4.0 4.0] :vz [-4.0 4.0] :spin [-20.0 20.0]}
        ticks (long (Math/floor (* (double seconds) 240.0)))
        target (.getCanonicalFile (io/file output))]
    (doseq [[key value] settings]
      (let [[low high] (get bounds key)]
        (when-not (and low (number? value) (<= low value high))
          (throw (ex-info "Configuration value outside the validated UI range"
                          {:parameter key :value value :range [low high]})))))
    (scene/initialize!)
    (try
      (scene/set-experiment! (= bodies 3))
      (scene/set-solver! ({:rigid 0 :xpbd 1 :fem 2} model) (double stiffness))
      (scene/reset! settings)
      (scene/bake-chunk! ticks ticks)
      (when (az/value scene/solver-failed)
        (throw (ex-info "FEM bake stopped with an invalid or unresolved step"
                        {:frames (az/value scene/count)})))
      (io/make-parents target)
      (with-open [writer (io/writer target)]
        (binding [*out* writer *print-length* nil *print-level* nil]
          (print "{:metadata ")
          (pr {:format :field-lab/cache-v1 :model model :body-count bodies
               :dt-seconds (/ 1.0 240.0) :ticks ticks :configuration settings
               :stiffness-Pa stiffness
               :stiffness-meaning (if (= model :fem) :young-modulus :effective-network-stiffness)
               :poisson-ratio (when (= model :fem) 0.4)
               :mesh (when (not= model :rigid)
                       (select-keys mesh/mesh [:points :triangles :mass-fractions]))})
          (println " :frames [")
          (dotimes [tick (inc ticks)]
            (let [summary (:bodies (az/value (cached-summary tick)))
                  particles (when (not= model :rigid)
                              (:bodies (az/value (cached-particles tick))))]
              (pr {:tick tick :time-seconds (/ tick 240.0)
                   :bodies (mapv (fn [index]
                                   {:id index :summary (if particles
                                                         (select-keys (nth summary index) [:position :velocity :time])
                                                         (nth summary index))
                                    :particles (when particles (nth particles index))})
                                 (range bodies))})
              (println)))
          (println "]}")))
      {:output (str target) :frames (inc ticks) :bodies bodies :model model}
      (finally
        (scene/shutdown!)))))

(defn -main [& [options]]
  (prn (run-job! (if options (edn/read-string options) {})))
  (shutdown-agents))
