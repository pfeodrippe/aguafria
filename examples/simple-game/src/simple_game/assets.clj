(ns simple-game.assets
  "JVM-side development catalog for source assets.

  This namespace is intentionally outside the native game dependency graph:
  release builds embed prepared native data and never ship the watcher or JVM
  catalog."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defonce ^:private state
  (atom {:revision 0
         :loaded-at-ms nil
         :models {}
         :missing []
         :error nil}))

(defn reload!
  "Reload the EDN catalog and verify every selected classpath asset."
  []
  (try
    (let [manifest-resource (io/resource "kenney/assets.edn")
          manifest (some-> manifest-resource slurp edn/read-string)
          models (into {} (map (juxt :id identity)) (:models manifest))
          missing (->> (:models manifest)
                       (keep (fn [{:keys [id resource]}]
                               (when-not (io/resource resource) id)))
                       vec)]
      (swap! state
             (fn [current]
               {:revision (inc (:revision current))
                :loaded-at-ms (System/currentTimeMillis)
                :license (:license manifest)
                :source-bundle (:source-bundle manifest)
                :models models
                :missing missing
                :error nil})))
    (catch Throwable error
      (swap! state assoc
             :error {:message (.getMessage error)
                     :class (str (class error))})
      (throw error))))

(defn snapshot
  "Return the complete inspectable development asset catalog."
  []
  (when-not (:loaded-at-ms @state)
    (reload!))
  @state)

(defn model
  "Return one selected model descriptor by keyword id."
  [id]
  (get-in (snapshot) [:models id]))
