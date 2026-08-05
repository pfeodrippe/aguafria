(ns simple-game.hot-reload-benchmark
  "Reproducible one-JVM hot-reload measurements over the live coco factory."
  (:require [aguafria.zig :as az]
            [aguafria.zig.benchmark :as benchmark]
            [clojure.walk :as walk]
            [simple-game.factory :as factory]
            [simple-game.game :as game]))

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

(defn run-all!
  "Run Simple Game's leaf and large-state cases in the same live native world."
  []
  {:simple (benchmark/summary (simple!))
   :complex (benchmark/summary (complex!))})
