(ns field-lab.live
  "Thread-safe Clojure authoring/control of the already-running native workbench."
  (:require [clojure.java.io :as io]
            [aguafria.zig :as az]
            [aguafria-examples-native.readback :as readback]
            [field-lab.app :as app]
            [field-lab.refined-job :as refined]
            [field-lab.coupled-cache-job :as coupled]
            [field-lab.mesh-cache :as cache]
            [field-lab.mesh-group :as group]
            [field-lab.physics :as physics]))

(defn status []
  (let [word (long (app/live-status))]
    {:frames (bit-and word 65535)
     :cursor (bit-and (bit-shift-right word 16) 65535)
     :baking? (bit-test word 32)
     :fem? (bit-test word 33)
     :failed? (bit-test word 34)
     :refined? (bit-test word 35)}))

(defn bake!
  [{:keys [model bodies seconds stiffness config]
    :or {model :fem bodies 1 seconds 2.0 stiffness 10000.0 config {}}}]
  (let [settings (merge (az/value (physics/defaults)) config)
        bounds {:radius [0.1 1.0] :mass [0.05 20.0] :height [0.1 8.0]
                :gravity [0.0 20.0] :restitution [0.0 1.0] :friction [0.0 1.0]
                :rolling [0.0 0.2] :vx [-4.0 4.0] :vz [-4.0 4.0] :spin [-20.0 20.0]}]
    (when-not (and (#{:rigid :xpbd :fem} model) (#{1 3} bodies)
                   (number? seconds) (< 0.0 seconds) (<= seconds 60.0)
                   (number? stiffness) (<= 1000.0 stiffness 100000.0))
      (throw (ex-info "Invalid model, body count, duration or modulus" {})))
    (doseq [[key value] settings]
      (let [[low high] (bounds key)]
        (when-not (and low (number? value) (Double/isFinite (double value)) (<= low value high))
          (throw (ex-info "Configuration outside UI bounds" {:key key :value value})))))
    (when-not (app/request-bake! settings bodies ({:rigid 0 :xpbd 1 :fem 2} model)
                                 (double stiffness) (double seconds))
      (throw (ex-info "Native request mailbox is busy" {})))
    {:queued :bake :model model :bodies bodies :seconds seconds}))

(defn seek! [tick]
  (when (or (:baking? (status)) (not (integer? tick)) (neg? tick))
    (throw (ex-info "Seek needs a finished/stopped cache and nonnegative integer tick" {})))
  (when-not (app/request-view! 3 tick)
    (throw (ex-info "Native request mailbox is busy" {})))
  {:queued :seek :tick tick})

(defn export! []
  (when (:baking? (status))
    (throw (ex-info "Stop or finish the bake before exporting" {})))
  (when-not (app/request-view! 4 0)
    (throw (ex-info "Native request mailbox is busy" {})))
  {:queued :export})

(defn stop! []
  (when-not (app/request-view! 8 0)
    (throw (ex-info "Native request mailbox is busy" {})))
  {:queued :stop})

(defn bake-refined!
  "Run on the calling REPL worker; use (future ...) for an asynchronous job.
  The UI adopts only complete results for the revision at which this job began."
  [options]
  (let [revision (app/live-revision)
        result (refined/build! options)
        owned (:cache result)]
    (if (app/request-cache! owned revision)
      (assoc (dissoc result :cache) :queued :refined-cache :source-revision revision)
      (do
        (cache/destroy! owned)
        (throw (ex-info "Completed cache could not be queued; native storage released" {}))))))

(defn refined-result []
  ({0 :pending 1 :published 2 :stale} (app/cache-result)))

(defn bake-three-refined!
  "Bake interacting FEM bodies on the calling worker, then publish the whole group."
  [options]
  (let [revision (app/live-revision)
        result (coupled/build! options)
        owned (:group result)]
    (if (app/request-group! owned revision)
      (assoc (dissoc result :group) :queued :refined-group :source-revision revision)
      (do
        (group/destroy! owned)
        (throw (ex-info "Completed group could not be queued; native storage released" {}))))))

(defn enable-scripting!
  "Development convenience for enabling the standalone-compatible external bridge."
  [directory]
  (let [directory (.getCanonicalFile (io/file directory))]
    (.mkdirs directory)
    (when-not (.isDirectory directory)
      (throw (ex-info "Cannot create scripting bridge directory" {:directory (str directory)})))
    (with-open [arena (java.lang.foreign.Arena/ofConfined)]
      (let [ticket (app/request-scripting! (.allocateFrom arena (str directory)))]
        (when (zero? ticket)
          (throw (ex-info "Native extension command queue is busy" {})))
        {:queued :enable-scripting :ticket ticket :directory (str directory)}))))

(defn capture-status
  "The renderer retains completion until acknowledge-capture! is called."
  []
  ({0 :idle 1 :copying-request 2 :queued 3 :rendering 4 :saved
    5 :unsupported-or-resource-failure 6 :file-failure}
   (readback/status)))

(defn acknowledge-capture! []
  (readback/acknowledge!))

(defn capture!
  "Queue a native Vulkan PPM capture of the next rendered frame, including its tick tag.
  Poll capture-status for file completion; this does not pause playback or seek."
  [path]
  (let [target (.getCanonicalFile (io/file path))]
    (io/make-parents target)
    (with-open [arena (java.lang.foreign.Arena/ofConfined)]
      (when-not (readback/request!
                 (.allocateFrom arena (.getPath target)))
        (throw (ex-info "Frame capture is busy or its path is invalid"
                        {:status (capture-status) :path (.getPath target)}))))
    {:queued :frame :path (.getPath target)}))
