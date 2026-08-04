(ns simple-game.hot-reload-benchmark
  "Reproducible one-JVM hot-reload measurements against the live game world."
  (:require [aguafria.zig :as az]
            [aguafria.zig.benchmark :as benchmark]
            [clojure.walk :as walk]
            [simple-game.game :as game]
            [simple-game.physics :as physics]))

(defn- value
  [native-value]
  (if (az/zig-value? native-value) (az/value native-value) native-value))

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
      (throw (ex-info "Benchmark could not find the expected game form"
                      {:declaration (:name descriptor)
                       :expected expected})))
    (assoc descriptor :body body)))

(defn simple!
  "Measure a leaf shader edit with immediately observable native behavior."
  []
  (benchmark/measure-edit!
   {:var #'game/shader-for-count
    :project :simple-game
    :complexity :simple
    :label "shader leaf"
    :edit #(replace-form % "(mod counter 5)"
                         (constantly '(mod counter 6)))
    :verify-change (fn [] {:shader (game/shader-for-count 5)})
    :verify-restore (fn [] {:shader (game/shader-for-count 5)})}))

(defn medium!
  "Edit the transition used by both Flecs input and nREPL clicks."
  []
  (let [_ (game/initialize!)
        previous (atom (:counter (value (game/snapshot))))
        verify (fn []
                 (let [counter (game/click!)
                       delta (- counter @previous)]
                   (reset! previous counter)
                   {:counter counter :delta delta}))]
    (benchmark/measure-edit!
     {:var #'game/advance-counter!
      :project :simple-game
      :complexity :medium
      :label "live Flecs click transition"
      :edit #(replace-form
              %
              "(+ (field (deref counter) value) 1)"
              (constantly '(+ (field (deref counter) value) 2)))
      :verify-change verify
      :verify-restore verify})))

(defn complex!
  "Add a compatible method to the real Box3D-backed particle type factory."
  []
  (let [_ (physics/initialize!)
        before (value (physics/snapshot))]
    (benchmark/measure-edit!
     {:var #'physics/ParticleType
      :project :simple-game
      :complexity :complex
      :label "Box3D ParticleType comptime struct"
      :edit
      (fn [descriptor]
        (update descriptor :body
                (fn [body]
                  (let [container (last body)
                        method '(fn-decl benchmark-marker
                                  {:attrs #{:public}}
                                  :- :u32 [] 17)]
                    (conj (vec (butlast body))
                          (apply list (concat container [method])))))))
      :verify-change
      (fn [] {:state-preserved? (= before (value (physics/snapshot)))})
      :verify-restore
      (fn [] {:state-preserved? (= before (value (physics/snapshot)))})})))

(defn run-all!
  "Run the three Simple Game cases and return compact EDN summaries."
  []
  {:simple (benchmark/summary (simple!))
   :medium (benchmark/summary (medium!))
   :complex (benchmark/summary (complex!))})
