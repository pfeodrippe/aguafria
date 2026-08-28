(ns racing-game.desktop-main
  "Start nREPL before loading Vulkan, then keep both in the same JVM."
  (:require [aguafria.zig :as az]
            [clojure.java.io :as io]
            [clojure.stacktrace :as stacktrace]
            [nrepl.server :as nrepl]
            [racing-game.build :as build]))

(defonce ^:private startup-request (atom 0))

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
            (az/await!)
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
      ((load-desktop!))
      (finally
        (nrepl/stop-server server)
        (when (.isFile port-file) (.delete port-file))
        (shutdown-agents)))))
