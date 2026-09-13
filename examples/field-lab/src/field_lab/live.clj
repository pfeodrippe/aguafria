(ns field-lab.live
  "Thread-safe Clojure authoring/control of the already-running native workbench."
  (:require [clojure.java.io :as io]
            [pitoco.frame :as frame]
            [aguafria.zig :as az]
            [aguafria-examples-native.readback :as readback]
            [field-lab.app :as app]
            [field-lab.nonlinear-job :as nonlinear]
            [field-lab.coupled-job :as joint]
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
        result (nonlinear/bake-cache! options)
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
        result (joint/bake-cache! options)
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

(defn- wait-for! [predicate description]
  (let [deadline (+ (System/nanoTime) 10000000000)]
    (loop []
      (when-not (predicate)
        (when (> (System/nanoTime) deadline)
          (throw (ex-info "Timed out awaiting native frame work"
                          {:operation description :capture (capture-status)})))
        (Thread/sleep 10)
        (recur)))))

(defn verify-captures!
  "Pause/seek and capture selected cached ticks; compare repeat-frame RGB hashes.
  The chosen ticks must fit the current completed cache. Leaves the last tick paused."
  [{:keys [ticks directory] :or {ticks [0 100 240 100] directory "exports/vulkan-readback"}}]
  (let [revision (app/live-revision)
        {:keys [frames baking?]} (status)]
    (when-not (and (not baking?) (seq ticks)
                   (every? #(and (integer? %) (<= 0 % (dec frames))) ticks))
      (throw (ex-info "Readback verification needs valid ticks in a completed cache" {})))
    (let [captures
          (mapv
            (fn [index tick]
              (seek! tick)
              (wait-for! #(= tick (:cursor (status))) :seek)
              ;; Let transient input/hover state settle before comparing complete UI images.
              (Thread/sleep 100)
              (acknowledge-capture!)
              (let [stem (io/file directory (str index "-tick-" tick))
                    ppm (str stem ".ppm")
                    png (str stem ".png")]
                (capture! ppm)
                (wait-for! #(#{:saved :file-failure :unsupported-or-resource-failure}
                               (capture-status)) :capture)
                (when-not (= :saved (capture-status))
                  (throw (ex-info "Native frame capture failed" {:status (capture-status)})))
                (let [metadata (frame/png! ppm png)]
                  (when-not (= [revision tick] ((juxt :revision :tick) metadata))
                    (throw (ex-info "Rendered frame does not match the requested cache state"
                                    {:expected [revision tick] :actual metadata})))
                  metadata)))
            (range) ticks)
          repeated (filter #(> (count %) 1) (vals (group-by :tick captures)))
          consistent? (every? #(apply = (map :rgb-sha256 %)) repeated)
          result {:revision revision :captures captures :repeated-pixels-identical? consistent?}]
      (spit (io/file directory "verification.edn") (str (pr-str result) "\n"))
      (when-not consistent?
        (throw (ex-info "Repeated frame pixels changed; inspect UI/input state and geometry" result)))
      result)))

(defn bake-scene!
  "Bake an authored solid scene and publish its cache plus provenance through Flecs.
  Use a future for offline work; the currently displayed cache remains available."
  ([description] (bake-scene! description {}))
  ([description options]
  (let [revision (app/live-revision)
        result (joint/bake-scene! description (select-keys options [:cancelled? :on-progress :on-frame]))
        owned (:group result)
        transferred? (volatile! false)]
    (try
      (let [source (str (pr-str (select-keys result [:scene :solver-version :maximum-step])) "\n")
            bytes (.getBytes source java.nio.charset.StandardCharsets/UTF_8)
            digest (.digest (java.security.MessageDigest/getInstance "SHA-256") bytes)
            hash (apply str (map #(format "%02x" (bit-and 255 %)) digest))
            file (io/file "exports" "scenes" (str hash ".edn"))
            bodies (get-in result [:scene :bodies])
            parameters {:gravity (mapv nonlinear/vector-map (take 3 (concat (map :gravity bodies) (repeat [0.0 0.0 0.0]))))
                        :floor (vec (take 3 (concat (map :floor? bodies) (repeat false))))
                        :source_hash (conj (mapv int hash) 0)}]
        (io/make-parents file)
        (with-open [output (io/output-stream file)] (.write output bytes))
        (when-let [before-publish (:before-publish options)] (before-publish))
        (when-not (app/request-scene! owned revision parameters)
          (throw (ex-info "Completed scene could not enter the publication mailbox" {})))
        (vreset! transferred? true)
        (assoc (dissoc result :group) :queued :scripted-scene :source-revision revision
               :source-file (.getCanonicalPath file) :source-sha256 hash))
      (catch Throwable error
        (when-not @transferred? (group/destroy! owned))
        (throw error))))))

(defonce authored-controller (atom nil))

(defn authored-job-source
  "UI presets are ordinary Clojure scene data layered on the same generic bake."
  [source ticks ipc?]
  (let [scene (load-file (if (= source 3) "scenes/solid-impact.clj" "scenes/three-ball-ipc.clj"))
        scene (if (= source 1) (assoc scene :bodies [(second (:bodies scene))]) scene)]
    (-> scene
        (assoc :title ({1 "Single soft ball" 2 "Three soft balls" 3 "Box and tetrahedron impact"} source))
        (assoc-in [:bake :seconds] (/ ticks 240.0))
        (assoc-in [:bake :contact-method] (if ipc? :ipc :discrete))
        (assoc-in [:bake :maximum-step] (if ipc? 0.0005 0.00005)))))

(defn- run-authored-job!
  [command running]
  (let [source (bit-and command 7)
        ticks (unsigned-bit-shift-right command 32)
        ipc? (bit-test command 3)
        cancelled? #(or (not @running) (#{0 6} (app/job-status)))
        finish! #(app/set-job-status! (if @running % 0))
        save! #(spit "build/authored-job-status.edn" (str (pr-str %) "\n"))]
    (if-not (app/transition-job! 2 3)
      (do (finish! 7) (save! {:status :cancelled :stage :queued}))
      (try
        (let [report! (fn [report]
                        (app/report-job-progress!
                         (min ticks (long (Math/floor (* 240.0 (:time report)))))
                         (:substeps report)))
              result (bake-scene!
                      (authored-job-source source ticks ipc?)
                      {:cancelled? cancelled? :on-progress report!
                       :on-frame (fn [{:keys [tick report]}]
                                   (app/report-job-progress! tick (:substeps report)))
                       :before-publish
                       (fn []
                         ;; Cancellation and publication compete for one native
                         ;; phase transition. A queued cache cannot be cancelled.
                         (when-not (app/transition-job! 3 4)
                           (throw (ex-info "Authored bake cancelled before publication" {:cancelled? true}))))})]
          (loop []
            (when (= :pending (refined-result))
              (Thread/sleep 20)
              (recur)))
          (let [published? (= :published (refined-result))]
            (finish! (if published? 5 9))
            (save! (assoc result :status (if published? :published :stale)))))
        (catch Throwable error
          (let [cancelled (or (cancelled?) (:cancelled? (ex-data error)))]
            (finish! (if cancelled 7 8))
            (save! {:status (if cancelled :cancelled :failed)
                    :message (ex-message error) :details (ex-data error)})))))))

(defn start-authored-controller!
  "Attach one optional Clojure worker to the current native UI command mailbox.
  Re-evaluating the UI does not create a second controller or a second window."
  []
  (locking authored-controller
    (if (and @authored-controller (not (realized? (:worker @authored-controller))))
      {:status (if @(:running @authored-controller) :enabled :stopping)}
      (let [running (atom true)]
        (app/take-job-command!)
        (app/set-job-status! 1)
        (let [worker (future
                       (try
                         (while @running
                           (let [command (app/take-job-command!)]
                             (when (pos? command) (run-authored-job! command running)))
                           (Thread/sleep 25))
                         (catch Throwable error
                           (spit "build/authored-controller-error.edn"
                                 (str (pr-str {:message (ex-message error) :details (ex-data error)}) "\n")))
                         (finally
                           (app/take-job-command!)
                           (app/set-job-status! 0))))]
          (reset! authored-controller {:running running :worker worker})
          {:status :enabled})))))

(defn stop-authored-controller!
  []
  (locking authored-controller
    (when-let [{:keys [running]} @authored-controller]
      (reset! running false)
      (app/cancel-scene-job!))
    (app/set-job-status! 0)
    {:status :stopping}))
