(ns simple-game.desktop-main
  "One JVM containing both the native macOS main loop and an inspectable nREPL."
  (:require [aguafria.zig :as az]
            [clojure.java.io :as io]
            [clojure.stacktrace :as stacktrace]
            [nrepl.server :as nrepl]))

(defonce ^:private startup-request (atom 0))

(defn retry-startup!
  "Retry loading/starting the desktop game after repairing a startup error."
  []
  (swap! startup-request inc))

(defn- wait-for-retry!
  [observed-request]
  (loop []
    (if (= observed-request @startup-request)
      (do
        (Thread/sleep 100)
        (recur))
      @startup-request)))

(defn- load-desktop!
  "Load on the first OS thread, but only after nREPL is already reachable."
  []
  (loop [request @startup-request]
    (let [outcome
          (try
            (require 'simple-game.desktop :reload)
            (az/await!)
            {:run (ns-resolve 'simple-game.desktop 'run!)}
            (catch Throwable error
              {:error error}))]
      (if-let [run (:run outcome)]
        run
        (let [error (:error outcome)]
          (binding [*out* *err*]
            (println "Aguafria desktop startup failed; this nREPL remains available.")
            (stacktrace/print-cause-trace error)
            (println "Repair/evaluate the code, then call")
            (println "  (simple-game.desktop-main/retry-startup!)")
            (flush))
          (recur (wait-for-retry! request)))))))

(defn -main
  [& _]
  (let [server (nrepl/start-server :bind "127.0.0.1" :port 0)
        port (:port server)
        port-file (io/file ".nrepl-port")]
    (spit port-file (str port))
    (println (str "Aguafria desktop nREPL listening on 127.0.0.1:" port))
    (println "Loading the native graph on the first OS thread; nREPL is already available.")
    (flush)
    (try
      (let [run (load-desktop!)]
        (println "Starting the GLFW/Vulkan loop on this JVM's first OS thread.")
        (flush)
        (run))
      (finally
        (nrepl/stop-server server)
        (when (.isFile port-file)
          (.delete port-file))
        (shutdown-agents)))))
