(ns simple-game.hot-reload-benchmark
  "Reproducible one-JVM hot-reload measurements over the live coco factory."
  (:require [aguafria.zig :as az]
            [aguafria.zig.benchmark :as benchmark]
            [clojure.walk :as walk]
            [simple-game.factory :as factory]
            [simple-game.game :as game]
            [simple-game.hot-reload-target :as target]))

(defn- replace-form
  [descriptor expected replacement]
  (let [replaced? (atom false)
        body (walk/postwalk
              (fn [form]
                (if (and (not @replaced?) (= expected (pr-str form)))
                  (do (reset! replaced? true) (replacement form))
                  form))
              (:body descriptor))]
    (when-not @replaced?
      (throw (ex-info "Expected Simple Game form was not found"
                      {:declaration (:name descriptor)
                       :expected expected})))
    (assoc descriptor :body body)))

(defn- world-address
  []
  (:world_address (az/value (game/snapshot))))

(defn- verified-world-value
  ([label expected actual address]
   (verified-world-value label expected actual address =))
  ([label expected actual address matches?]
   (let [same-world? (= address (world-address))]
     (when-not (and (matches? expected actual) same-world?)
       (throw (ex-info "Hot-reload behavior did not become observable"
                       {:label label
                        :expected expected
                        :actual actual
                        :same-world? same-world?})))
     {:value actual
      :same-world? same-world?})))

(defn- close-f32?
  [expected actual]
  (< (Math/abs (- (double expected) (double actual))) 1.0e-6))

(defn- fresh-duration
  [context]
  (float (+ 0.4 (/ (mod (:fresh-value context) 100000) 1000000.0))))

(defn- await-live-world!
  []
  (game/initialize!)
  (doseq [module '[simple-game.audio
                   simple-game.factory
                   simple-game.game
                   simple-game.physics]]
    (az/await! module)))

(defn simple!
  "Measure a compatible factory tuning edit while Flecs state stays live."
  ([] (simple! 0.45))
  ([duration]
   (await-live-world!)
   (let [_ (factory/press-duration)
         address (world-address)]
     (benchmark/measure-edit!
      {:var #'factory/press-duration
       :project :simple-game
       :complexity :simple
       :label "press-duration tuning leaf"
       :edit #(assoc % :body [duration])
       :verify-change
       (fn []
         (verified-world-value "press-duration change"
                               duration (factory/press-duration) address
                               close-f32?))
       :verify-restore
       (fn []
         (verified-world-value "press-duration restore"
                               0.35 (factory/press-duration) address
                               close-f32?))}))))

(defn complex!
  "Measure a compatible edit to the factory's large native snapshot projection."
  ([] (complex! 1))
  ([identity-depth]
   (await-live-world!)
   (let [address (world-address)
         baseline (az/value (factory/snapshot))]
     (benchmark/measure-edit!
      {:var #'factory/snapshot
       :project :simple-game
       :complexity :complex
       :label "live FactorySnapshot projection"
       :edit #(replace-form
               %
               "(>= simple-game.factory/houses-completed-count house-goal)"
               (fn [form]
                 (nth (iterate (fn [nested] (list 'and nested true)) form)
                      identity-depth)))
       :verify-change
       (fn []
         (verified-world-value "FactorySnapshot change"
                               baseline (az/value (factory/snapshot)) address))
       :verify-restore
       (fn []
         (verified-world-value "FactorySnapshot restore"
                               baseline (az/value (factory/snapshot)) address))}))))

(defn simple-series!
  "Measure fresh leaf artifacts while preserving the same Flecs world."
  ([] (simple-series! 5))
  ([samples]
   (await-live-world!)
   (let [_ (factory/press-duration)
         address (world-address)]
     (benchmark/measure-fresh-edits!
      {:var #'factory/press-duration
       :project :simple-game
       :complexity :simple
       :label "press-duration fresh leaf"
       :samples samples
       :edit (fn [declaration context]
               (assoc declaration :body [(fresh-duration context)]))
       :verify-change
       (fn [context]
         (let [duration (fresh-duration context)]
           (verified-world-value "fresh press-duration"
                                 duration (factory/press-duration) address
                                 close-f32?)))
       :verify-restore
       #(verified-world-value "press-duration series restore"
                              0.35 (factory/press-duration) address
                              close-f32?)}))))

(defn medium-series!
  "Measure fresh leaf edits through an existing cross-namespace native caller."
  ([] (medium-series! 5))
  ([samples]
   (await-live-world!)
   (az/await! 'simple-game.hot-reload-target)
   (let [_ (target/press-duration-caller)
         address (world-address)]
     (benchmark/measure-fresh-edits!
      {:var #'factory/press-duration
       :project :simple-game
       :complexity :medium
       :label "press-duration through live caller"
       :samples samples
       :edit (fn [declaration context]
               (assoc declaration :body [(fresh-duration context)]))
       :verify-change
       (fn [context]
         (let [duration (fresh-duration context)]
           {:direct
            (verified-world-value "fresh press-duration direct"
                                  duration (factory/press-duration) address
                                  close-f32?)
            :caller
            (verified-world-value "fresh press-duration caller"
                                  duration (target/press-duration-caller) address
                                  close-f32?)}))
       :verify-restore
       (fn []
         {:direct
          (verified-world-value "press-duration direct restore"
                                0.35 (factory/press-duration) address close-f32?)
          :caller
          (verified-world-value "press-duration caller restore"
                                0.35 (target/press-duration-caller) address
                                close-f32?)})}))))

(defn complex-series!
  "Measure fresh large snapshot bodies against the same native world."
  ([] (complex-series! 5))
  ([samples]
   (await-live-world!)
   (let [address (world-address)
         baseline (az/value (factory/snapshot))]
     (benchmark/measure-fresh-edits!
      {:var #'factory/snapshot
       :project :simple-game
       :complexity :complex
       :label "live FactorySnapshot fresh projection"
       :samples samples
       :edit (fn [declaration context]
               (replace-form
                declaration
                "(>= simple-game.factory/houses-completed-count house-goal)"
                (fn [form]
                  (list 'and form
                        (list 'aguafria.keyword/==
                              (:fresh-value context)
                              (:fresh-value context))))))
       :verify-change
       (fn [_]
         (verified-world-value "fresh FactorySnapshot"
                               baseline (az/value (factory/snapshot)) address))
       :verify-restore
       #(verified-world-value "FactorySnapshot series restore"
                              baseline (az/value (factory/snapshot)) address)}))))

(defn run-distributions!
  "Run fresh simple/medium/complex samples in one persistent game world."
  ([] (run-distributions! 5))
  ([samples]
   {:simple (benchmark/summary (simple-series! samples))
    :medium (benchmark/summary (medium-series! samples))
    :complex (benchmark/summary (complex-series! samples))}))

(defn run-all!
  "Run Simple Game's leaf and large-state cases in the same live native world."
  []
  {:simple (benchmark/summary (simple!))
   :complex (benchmark/summary (complex!))})
