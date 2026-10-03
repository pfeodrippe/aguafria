(ns racing-game.desktop-main
  "Start nREPL before loading Vulkan, then keep both in the same JVM."
  (:require [aguafria.zig :as a]
            [aguafria.spirv :as spirv]
            [aguafria.zig.runtime :as runtime]
            [clojure.java.io :as io]
            [clojure.stacktrace :as stacktrace]
            [nrepl.server :as nrepl]
            [racing-game.build :as build]))

(defonce ^:private startup-request (atom 0))

(defonce shader-status (atom {:state :idle}))

(defn- shader-stamp []
  (let [file (io/file (build/project-root) "src/racing_game/shaders.clj")]
    [(.lastModified file) (.length file)]))

(defn start-shader-watcher!
  "Compile off-thread; the renderer publishes replacement pipelines at a frame boundary."
  []
  (when (:reloadable? (a/configuration))
    (future
      (loop [observed (shader-stamp)]
        (Thread/sleep 250)
        (let [current (shader-stamp)]
          (when (not= observed current)
            (try
              (build/prepare-shaders!)
              (runtime/precompile-function!
               'aguafria-examples-native.renderer/request-shader-reload!)
              ((ns-resolve 'aguafria-examples-native.renderer 'request-shader-reload!))
              (reset! shader-status {:state :queued :at (System/currentTimeMillis)})
              (catch InterruptedException error (throw error))
              (catch Throwable error
                (reset! shader-status {:state :error
                                       :message (.getMessage error)
                                       :diagnostics (spirv/diagnostics error)})
                (binding [*out* *err*]
                  (println "Shader reload failed; keeping working pipelines:")
                  (println (spirv/diagnostics error))
                  (flush)))))
          (recur current))))))

(defn retry-startup!
  []
  (swap! startup-request inc))

(defn- wait-for-retry!
  [observed]
  (loop []
    (if (= observed @startup-request)
      (do (Thread/sleep 100) (recur))
      @startup-request)))

(defn- load-desktop!
  []
  (loop [request @startup-request]
    (let [outcome
          (try
            (build/prepare!)
            (require 'racing-game.monitor :reload)
            ;; The entry module already links its transitive native graph.
            ;; Awaiting every registered namespace also forces standalone JVM
            ;; wrappers for thousands of unused Flecs/GLFW binding declarations.
            (a/await! 'racing-game.monitor)
            {:run (ns-resolve 'racing-game.monitor 'run!)}
            (catch Throwable error {:error error}))]
      (if-let [run (:run outcome)]
        run
        (do
          (binding [*out* *err*]
            (println "Racing startup failed; nREPL remains available.")
            (stacktrace/print-cause-trace (:error outcome))
            (println "Repair/evaluate code, then call (racing-game.desktop-main/retry-startup!)")
            (flush))
          (recur (wait-for-retry! request)))))))

(defn -main
  [& _]
  (let [server (nrepl/start-server :bind "127.0.0.1" :port 0)
        port (:port server)
        port-file (io/file ".nrepl-port")]
    (spit port-file (str port))
    (println (str "Aguafria racing nREPL listening on 127.0.0.1:" port))
    (flush)
    (try
      (let [run (load-desktop!)
            watcher (start-shader-watcher!)]
        (try (run)
             (finally (when watcher (future-cancel watcher)))))
      (finally
        (nrepl/stop-server server)
        (when (.isFile port-file) (.delete port-file))
        (shutdown-agents)))))
