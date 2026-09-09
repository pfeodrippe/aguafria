(ns la-professeure.core
  "One JVM: nREPL workers edit declarations; the main thread owns the native stage."
  (:require [aguafria.zig :as az]
            [clojure.java.io :as io]
            [clojure.stacktrace :as stacktrace]
            [la-professeure.build :as build]
            [la-professeure.dialogue :as dialogue]
            [nrepl.server :as nrepl]))

(defonce commands (java.util.concurrent.ConcurrentLinkedQueue.))
(defonce status (atom {:state :starting}))
(defonce startup-request (atom 0))

(def shader-reload-enabled?
  "Separate from Zig optimization: dev can use ReleaseFast. No watcher exists in the standalone entry point."
  (and (:reloadable? (az/configuration))
       (not= "false" (System/getProperty "la-professeure.shader-reload" "true"))))

(defn retry-startup! [] (swap! startup-request inc))

(defn on-render!
  "Queue a zero-argument function on the render thread; returns a promise.
  Dereferencing it returns {:value ...} or {:error throwable}."
  [f]
  (let [result (promise)]
    (.add commands [f result])
    result))

(defn- drain! []
  (loop []
    (when-let [[f result] (.poll commands)]
      (deliver result (try {:value (f)} (catch Throwable e {:error e})))
      (recur))))

(defn- shader-stamp []
  (mapv #(let [f (io/file (build/root) "resources/shaders" %)] [(.lastModified f) (.length f)])
        ["mesh.vert" "mesh.frag"]))

(defn start-shader-watcher!
  "Compile away from the render thread, then publish a complete pipeline there."
  []
  (when shader-reload-enabled?
    (future
      (loop [observed (shader-stamp)]
        (Thread/sleep 250)
        (let [current (shader-stamp)]
          (when (not= observed current)
            (try
              (let [started (System/nanoTime)
                    _ (build/shaders!)
                    compiled (System/nanoTime)
                    result @(on-render! #((ns-resolve 'la-professeure.gpu 'reload-shaders!)))]
                (when-not (true? (:value result))
                  (throw (ex-info "Vulkan rejected shader replacement; old pipeline retained" result)))
                (swap! status assoc :shaders {:state :reloaded :at (System/currentTimeMillis)
                                             :compile-ms (/ (- compiled started) 1e6)
                                             :total-ms (/ (- (System/nanoTime) started) 1e6)}))
              (catch Throwable e
                (swap! status assoc :shaders {:state :error :message (.getMessage e)
                                             :diagnostics (:output (ex-data e))})
                (binding [*out* *err*]
                  (println "Shader reload failed; keeping working pipeline:" (.getMessage e))
                  (when-let [output (:output (ex-data e))] (println output))
                  (flush)))))
          (recur current))))))

(defn- load-scene! []
  (loop [request @startup-request]
    (let [result (try
                   (require 'la-professeure.scene :reload)
                   (az/await! 'la-professeure.scene)
                   :ready
                   (catch Throwable e e))]
      (if (= result :ready)
        :ready
        (do
          (reset! status {:state :startup-error :error result})
          (binding [*out* *err*]
            (loop [error result]
              (if-let [cause (.getCause error)] (recur cause)
                (println (.getMessage error)))))
          (println "nREPL remains available. Fix code, then call (la-professeure.core/retry-startup!).")
          (flush)
          (while (= request @startup-request) (Thread/sleep 100))
          (recur @startup-request))))))

(defonce story-watcher (atom nil))

(defn start-story-watcher!
  "Compile Markdown with the native authoring tool, then swap the validated asset on the render thread."
  []
  (when-let [previous @story-watcher] (future-cancel previous))
  (when (:reloadable? (az/configuration))
    (reset! story-watcher
      (future
        (loop [published nil]
          (let [next-hash
                (try
                  (let [source (build/story-source)
                        hash [(str source) (dialogue/digest (slurp source :encoding "UTF-8"))]]
                    (when (not= hash published)
                      (build/compile-story!)
                      (let [result @(on-render! #((ns-resolve 'la-professeure.scene 'reload-story!)))]
                        (when-not (true? (:value result))
                          (throw (ex-info "Native game rejected dialogue replacement" result))))
                      (swap! status assoc :dialogue {:state :reloaded :source (str source)
                                                    :at (System/currentTimeMillis)}))
                    hash)
                  (catch InterruptedException e (throw e))
                  (catch Throwable e
                    (swap! status assoc :dialogue {:state :error :message (.getMessage e)
                                                  :source (:source (ex-data e))})
                    published))]
            (Thread/sleep 500)
            (recur next-hash)))))))

(defn start-asset-watcher!
  "Development only. Watch a local/iPad-synced export; validate before publication."
  []
  (when (:reloadable? (az/configuration))
    (future
      (let [stamp #(mapv (fn [f] [(.lastModified f) (.length f)])
                         [(build/animation-file)
                          (io/file (build/root) "resources/art/corridor.png")
                          (io/file (build/root) "resources/fonts/LibreBaskerville.ttf")])]
        (loop [observed (stamp)]
          (Thread/sleep 350)
          (let [current (stamp)]
            (when (not= observed current)
              (try
                (build/atlas!)
                (let [result @(on-render! #((ns-resolve 'la-professeure.scene 'reload-visuals!)))]
                  (when-let [error (:error result)] (throw error))
                  (swap! status assoc :assets {:state :reloaded :at (System/currentTimeMillis)}))
                (catch Throwable error
                  (swap! status assoc :assets {:state :error :message (.getMessage error)})
                  (binding [*out* *err*] (println "Asset reload failed:" (.getMessage error))))))
            (recur current)))))))

(defn- run-stage! []
  (let [initialize (ns-resolve 'la-professeure.scene 'initialize!)
        tick (ns-resolve 'la-professeure.scene 'tick!)
        snapshot (ns-resolve 'la-professeure.scene 'snapshot)
        shutdown (ns-resolve 'la-professeure.scene 'shutdown!)]
    (try
      (when-not (initialize)
        (throw (ex-info "Stage failed to initialize; inspect Vulkan/GLFW diagnostics" {})))
      (loop []
        (drain!)
        (let [continue?
              (try
                (when (tick)
                  (let [value (snapshot)]
                    (try (swap! status assoc :state :running :scene (az/value value))
                         (finally (az/close! value))))
                  true)
                (catch clojure.lang.ExceptionInfo e
                  ;; A failed live declaration must not take down nREPL or release
                  ;; the native window. Fix/evaluate it and the next iteration retries.
                  (swap! status assoc :state :runtime-error :error e)
                  (Thread/sleep 50)
                  true))]
          (when continue? (recur))))
      (finally
        (shutdown)
        (reset! status {:state :closed})))))

(defn -main [& _]
  (let [server (nrepl/start-server :bind "127.0.0.1" :port 0)
        port (:port server) port-file (io/file ".nrepl-port")]
    (spit port-file (str port))
    (println "La Professeure nREPL:" port)
    (flush)
    (try
      (load-scene!)
      (let [watchers [(start-shader-watcher!) (start-asset-watcher!) (start-story-watcher!)]]
        (try (run-stage!)
             (finally
               (doseq [watcher watchers :when watcher] (future-cancel watcher))
               (when-let [watcher @story-watcher] (future-cancel watcher)))))
      (finally
        (nrepl/stop-server server)
        (when (and (.isFile port-file) (= (str port) (slurp port-file))) (.delete port-file))
        (shutdown-agents)))))

(comment
  ;; Start with clojure -M:dev:desktop, then connect CIDER/Calva to .nrepl-port.
  @status

  ;; Evaluate ONLY animation-fps's az/defconst in scene.clj (8.0 -> 4.0),
  ;; Dialogue text and choices live ONLY in the Markdown selected in dialogue.edn.
  ;; Save that file or select another :source; the watcher reloads it automatically.
  (az/await! 'la-professeure.scene)

  ;; Native resource operations must run on the render thread.
  (deref (on-render! #(la-professeure.scene/reload-track!)) 5000 :timeout)

  ;; Export a WAV from Bitwig to resources/demo/lesson.wav. The stage watches it.
  ;; For reliable replacement, export elsewhere, then atomically rename it here.
  )
